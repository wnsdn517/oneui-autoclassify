package com.auto.classify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Light / dark choice: "system" (default), "light" or "dark". */
object ThemeChoice {
    const val KEY = "theme"
    val values = listOf("system", "light", "dark")
    val names = listOf("시스템 설정", "라이트", "다크")
    var mode by mutableStateOf("system")
}

/** Folder background in the layout editor: "plain" (default) or "glass" (Liquid Glass blur). */
object FolderStyle {
    const val KEY = "folder_style"
    val values = listOf("plain", "glass")
    val names = listOf("기본", "Liquid Glass")
    var mode by mutableStateOf("plain")
}

/** HyperOS look with miuix's own color scheme, light or dark as chosen. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val mode = ThemeChoice.mode
    val controller = remember(mode) {
        ThemeController(
            when (mode) {
                "light" -> ColorSchemeMode.MonetLight
                "dark" -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
        )
    }
    MiuixTheme(controller = controller, content = content)
}
