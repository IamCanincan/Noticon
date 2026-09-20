package com.iamcanincan.noticon.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamcanincan.noticon.R
import com.iamcanincan.noticon.data.ModulePrefs
import com.iamcanincan.noticon.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面。
 *
 * 视觉约定（改样式时照着来，别再各写各的）：
 * - 卡片统一 24dp 圆角 + 1dp outlineVariant 描边，背景 surface；只有「已选中」
 *   和「状态条」这类需要突出的才用 container 色。
 * - 每个区块的标题走 [SectionLabel]；每条可选项左侧都有 44dp 的图标底板，
 *   所以开关组的分隔线要缩进到图标右边（[IconBadge] 宽度 + 间距）。
 * - 只暴露一个真正的选择 —— 图标模式；其余开关都是从它推导或独立的布尔项。
 *   每次改动立即落盘，模块侧下次被挂钩时就能读到，不需要点"保存"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { ModulePrefs.of(context).also { ModulePrefs.seedIfAbsent(it) } }

    var enabled by remember { mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_ENABLED, true)) }
    var mode by remember {
        mutableIntStateOf(prefs.getInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON))
    }
    var includeProxy by remember {
        mutableStateOf(prefs.getBoolean(ModulePrefs.KEY_INCLUDE_PROXY, false))
    }

    val version = remember { installedVersion(context) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BrandHeader()
            StatusStrip(enabled = enabled, mode = mode)

            SectionLabel("图标模式")
            ModeOption(
                icon = R.drawable.ic_mode_color,
                title = "彩色桌面图标",
                description = "把通知里认不出的灰白小图标，换成应用在桌面上那个图标，颜色原样保留。",
                selected = mode == ModulePrefs.MODE_LAUNCHER_ICON,
                onClick = {
                    mode = ModulePrefs.MODE_LAUNCHER_ICON
                    prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON).apply()
                }
            )
            ModeOption(
                icon = R.drawable.ic_mode_mono,
                title = "系统黑白通知",
                description = "把应用自己给的小图标压成单色剪影，由系统按主题统一上色，风格和其它通知一致。",
                selected = mode == ModulePrefs.MODE_MONOCHROME,
                onClick = {
                    mode = ModulePrefs.MODE_MONOCHROME
                    prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_MONOCHROME).apply()
                }
            )

            SectionLabel("行为")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column {
                    SwitchRow(
                        icon = R.drawable.ic_power,
                        title = "启用模块",
                        description = "关闭后所有通知都不处理",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.edit().putBoolean(ModulePrefs.KEY_ENABLED, it).apply()
                        }
                    )
                    GroupDivider()
                    SwitchRow(
                        icon = R.drawable.ic_relay,
                        title = "处理代发通知",
                        description = "推送 SDK 代投的通知，实际发件应用与通知包名不一致",
                        checked = includeProxy,
                        onCheckedChange = {
                            includeProxy = it
                            prefs.edit().putBoolean(ModulePrefs.KEY_INCLUDE_PROXY, it).apply()
                        }
                    )
                }
            }

            SectionLabel("更新")
            UpdateCard(
                version = version,
                state = update,
                onCheck = {
                    update = UpdateState.Checking
                    scope.launch {
                        // 联网请求不能跑在主线程上，扔到 IO 再回来更新界面状态
                        val result = withContext(Dispatchers.IO) { UpdateChecker.check(version) }
                        update = result.toState(version)
                    }
                },
                onOpenPage = { url -> openInBrowser(context, url) }
            )

            ScopeCard()
            Footer(version)
        }
    }
}

/** 顶部的品牌头：图标 + 名称 + 一句话说明 */
@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(58.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Noticon",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "未适配主题的通知小图标，换成应用图标或单色",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 状态条：一行讲清「开没开、用的哪个模式」，第二行是生效时机的说明。
 * 用 container 色和下面的设置卡区分开，但不做成一整块大卡片。
 */
@Composable
private fun StatusStrip(enabled: Boolean, mode: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) scheme.primaryContainer else scheme.surfaceVariant,
        contentColor = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 状态点：启用时是实心主色，停用时是中性灰
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (enabled) scheme.primary else scheme.outline)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (enabled) "已启用 · ${mode.modeName}" else "已停用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "改动立即生效，新收到的通知会按新模式显示；已经在通知栏里的那条要等它重新加载",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 图标模式的中文名。两处（状态条、模式名）共用，避免写岔 */
private val Int.modeName: String
    get() = if (this == ModulePrefs.MODE_MONOCHROME) "系统黑白通知" else "彩色桌面图标"

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/** 可选项左侧的图标底板。宽度固定，开关组的分隔线缩进按它算 */
private val IconBadgeSize = 44.dp

@Composable
private fun IconBadge(@DrawableRes icon: Int, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(IconBadgeSize)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primary else scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeOption(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon = icon, selected = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.78f)
                    else scheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            RadioDot(selected = selected)
        }
    }
}

/** 自绘单选点：选中时是实心圆 + 内点，未选中时是描边圆环 */
@Composable
private fun RadioDot(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (selected) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(scheme.onPrimary))
        }
    } else {
        // 未选中的环：外层 outline 画环，内层用卡片底色挖空（卡片此时就是 surface）
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(scheme.outline),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(15.dp).clip(CircleShape).background(scheme.surface))
        }
    }
}

/** 开关组内部的分隔线：左边缩进到图标右侧，不要顶到卡片边缘 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp + IconBadgeSize + 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SwitchRow(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, selected = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ScopeCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(icon = R.drawable.ic_scope, selected = false)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "关于作用域",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "模块只作用于「系统界面」，作用域由模块自己固定，不需要手动勾选。"
                        + "如果发现模块不生效，先在框架的模块详情里确认模块已启用、"
                        + "作用域里有「系统界面」，然后重启设备。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Footer(version: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            "版本 $version · MIT 许可 · 无统计、无广告",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 本机 versionName。取不到就显示 ? —— 只用于展示和版本比对，不影响模块行为 */
private fun installedVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

/** 检查更新的界面状态 */
private sealed interface UpdateState {

    /** 还没查过 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /**
     * 查完了：一行结论；发现新版本时额外带发布页地址。
     * [ok] 决定这句话用成功色还是错误色 —— 用户扫一眼就能分清"没事"和"出问题了"。
     */
    data class Done(val message: String, val url: String? = null, val ok: Boolean = true) : UpdateState
}

/**
 * 把检查结果翻成给用户看的一句话。
 *
 * 「已是最新」要分两种说法：远端和本地一样，就是普通的最新；
 * 远端比本地还旧，说明本地装的是还没发布的版本（自己构建的），
 * 这时候提示「去升级到更老的版本」显然不对。
 */
private fun UpdateChecker.Result.toState(current: String): UpdateState = when (this) {
    is UpdateChecker.Result.Newer ->
        UpdateState.Done("发现新版本 $version（当前 $current）", url, ok = true)

    is UpdateChecker.Result.UpToDate ->
        if (version == current) UpdateState.Done("已是最新版本")
        else UpdateState.Done("已是最新（本地 $current 比已发布的 $version 还新）")

    is UpdateChecker.Result.Failed ->
        UpdateState.Done("检查失败：$reason", ok = false)
}

/** 用系统浏览器打开链接；设备上没有浏览器时静默忽略，不影响界面其余部分 */
private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun UpdateCard(
    version: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onOpenPage: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = R.drawable.ic_update, selected = false)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "检查更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "当前版本 $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "只有点下面的按钮才会联网：先直连 GitHub，连不上时走公共加速镜像。"
                    + "这是本应用唯一的联网行为，没有统计也没有上报。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )

            val done = state as? UpdateState.Done
            if (done != null) {
                Spacer(Modifier.height(12.dp))
                ResultChip(done)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val checking = state is UpdateState.Checking
                Button(onClick = onCheck, enabled = !checking) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (checking) "检查中…" else "检查更新")
                }
                val url = done?.url
                if (url != null) {
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { onOpenPage(url) }) { Text("打开发布页") }
                }
            }
        }
    }
}

/** 检查结果：成功走 secondaryContainer，失败走 errorContainer，一眼能分清 */
@Composable
private fun ResultChip(done: UpdateState.Done) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (done.ok) scheme.secondaryContainer else scheme.errorContainer,
        contentColor = if (done.ok) scheme.onSecondaryContainer else scheme.onErrorContainer
    ) {
        Text(
            done.message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        )
    }
}
