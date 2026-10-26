package com.puresnap.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// PureSnap 高级宝石蓝与冰川蓝视觉色系（Sapphire Blue & Glacier Azure）
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),            // 皇家宝石蓝
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),   // 柔和冰晶蓝容器
    onPrimaryContainer = Color(0xFF1E3A8A), // 深邃海军蓝文字
    secondary = Color(0xFF0284C7),          // 天际蔚蓝
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE), // 浅云蓝容器
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFF4F46E5),           // 靛青蓝点缀
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF312E81),
    background = Color(0xFFF2F6FC),         // 微冷冰蓝灰底色，衬托纯白卡片立体层次
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),            // 纯白高光卡片表面
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EFFA),     // 浅蓝灰内嵌槽位底色
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFBFDBFE)             // 柔和蓝调微描边
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),            // 亮钻钴蓝
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1E3A8A),   // 深海蓝容器
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF38BDF8),          // 极光天蓝
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF075985),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF312E81),
    onTertiaryContainer = Color(0xFFE0E7FF),
    background = Color(0xFF080D1A),         // 深邃午夜蓝黑底色
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF10192D),            // 午夜蓝卡片表面
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF192642),     // 高阶深蓝次级表面
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3D5E)
)

// 大圆角卡片规范（24dp 核心卡片 + 胶囊按钮）
val PureSnapShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

val PureSnapTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun PureSnapTheme(
    darkThemeMode: Int = 0, // 0: 跟随系统, 1: 浅色, 2: 深色
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (darkThemeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = PureSnapShapes,
        typography = PureSnapTypography,
        content = content
    )
}
