package com.jasongzy.frpmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.ThemeMode
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.dynamiccolor.ColorSpec

@Composable
fun FrpMobileTheme(
    seedColor: Int,
    mode: ThemeMode,
    languageMode: LanguageMode,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(languageMode) {
        val tags = when (languageMode) {
            LanguageMode.System -> ""
            LanguageMode.Chinese -> "zh-CN"
            LanguageMode.English -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }
    DynamicMaterialTheme(
        seedColor = Color(seedColor),
        isDark = when (mode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        },
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        animate = true,
        content = content,
    )
}
