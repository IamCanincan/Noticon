package com.iamcanincan.noticon.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iamcanincan.noticon.ui.theme.NoticonTheme

/**
 * 模块的设置界面。
 *
 * 只负责读写自己的 SharedPreferences；真正的挂钩工作发生在 SystemUI 进程里，
 * 由 [com.iamcanincan.noticon.entry.NoticonModule] 通过 LibXposed 的远程配置读到这里的设置。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoticonTheme {
                SettingsScreen()
            }
        }
    }
}
