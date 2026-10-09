package com.astrbot.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AstrBotPurple80,
    secondary = AstrBotPurpleGrey80,
    tertiary = AstrBotPink80
)

private val LightColorScheme = lightColorScheme(
    primary = AstrBotPurple40,
    secondary = AstrBotPurpleGrey40,
    tertiary = AstrBotPink40
)

@Composable
fun AstrBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 固定使用品牌配色，保持 AstrBot 视觉一致性
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
