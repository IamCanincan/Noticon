package com.iamcanincan.noticon.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iamcanincan.noticon.R
import com.iamcanincan.noticon.data.ModulePrefs

/**
 * 设置界面。
 *
 * 只暴露一个真正的选择 —— 图标模式；其余开关都是从它推导或独立的布尔项。
 * 每次改动立即落盘，模块侧下次被挂钩时就能读到，不需要点"保存"。
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

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Noticon", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeroCard()

            StatusCard(enabled = enabled, mode = mode)

            SectionLabel("图标模式")
            ModeOption(
                title = "彩色桌面图标",
                description = "把通知里认不出的灰白小图标，换成应用在桌面上那个图标，颜色原样保留。",
                selected = mode == ModulePrefs.MODE_LAUNCHER_ICON,
                onClick = {
                    mode = ModulePrefs.MODE_LAUNCHER_ICON
                    prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_LAUNCHER_ICON).apply()
                }
            )
            ModeOption(
                title = "系统黑白通知",
                description = "把应用自己给的小图标压成单色剪影，由系统按主题统一上色，风格和其它通知一致。",
                selected = mode == ModulePrefs.MODE_MONOCHROME,
                onClick = {
                    mode = ModulePrefs.MODE_MONOCHROME
                    prefs.edit().putInt(ModulePrefs.KEY_MODE, ModulePrefs.MODE_MONOCHROME).apply()
                }
            )

            SectionLabel("行为")

            SwitchRow(
                title = "启用模块",
                description = "关闭后所有通知都不处理",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(ModulePrefs.KEY_ENABLED, it).apply()
                }
            )

            SwitchRow(
                title = "处理代发通知",
                description = "推送 SDK 代投的通知，实际发件应用与通知包名不一致",
                checked = includeProxy,
                onCheckedChange = {
                    includeProxy = it
                    prefs.edit().putBoolean(ModulePrefs.KEY_INCLUDE_PROXY, it).apply()
                }
            )

            NoticeCard()
            Footer(context)
        }
    }
}

@Composable
private fun HeroCard() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text("通知小图标修复", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "未适配主题的图标换成应用图标，已适配的不动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, mode: Int) {
    val modeName = if (mode == ModulePrefs.MODE_MONOCHROME) "系统黑白通知" else "彩色桌面图标"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (enabled) "已启用" else "已停用",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text("当前模式：$modeName", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "改动立即生效，新收到的通知会按新模式显示；已经在通知栏里的那条要等它重新加载",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, RoundedCornerShape(24.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.surfaceVariant else scheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioDot(selected = selected)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 自绘单选点：选中时是实心圆，未选中时是描边圆环 */
@Composable
private fun RadioDot(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(2.dp, if (selected) scheme.primary else scheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(11.dp)
                    .background(scheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
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
}

@Composable
private fun NoticeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("关于作用域", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "模块只作用于「系统界面」，作用域由模块自己固定，不需要手动勾选。"
                    + "部分框架（如 Vector 2.2）不会自动读取模块声明的作用域，"
                    + "如果发现模块不生效，先在框架的模块详情里确认作用域里有「系统界面」。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Footer(context: Context) {
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            "版本 $version · MIT 许可 · 无联网、无统计、无广告",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
