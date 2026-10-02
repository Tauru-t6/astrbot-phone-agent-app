package com.tauru.astrbotphoneagent.ui.theme

import androidx.compose.ui.graphics.Color

// ───────── 浅色主题：水面（冷调主基调） ─────────
val WaterTop = Color(0xFFCFEBF1)        // 冰水蓝
val WaterMid = Color(0xFFE2F1EE)        // 水雾白
val WaterBottom = Color(0xFFFDE7CB)     // 暖纸黄（与冷蓝形成对比锚点）

// ───────── 浅色主题：玻璃面板 ─────────
val GlassSurface = Color(0xFFFFFFFF)
val GlassBorder = Color(0x66FFFFFF)
val GlassBorderSoft = Color(0x33FFFFFF)

// ───────── 浅色主题：文本 ─────────
val TextPrimary = Color(0xFF0B1E2A)
val TextSecondary = Color(0xFF5B6B75)
val TextTertiary = Color(0xFF95A1A8)

// ───────── 深色主题：背景渐变（星夜蓝紫调） ─────────
val DarkWaterTop = Color(0xFF1A1F3A)        // 深夜蓝
val DarkWaterMid = Color(0xFF2D3250)        // 暮光紫
val DarkWaterBottom = Color(0xFF424769)     // 灰紫

// ───────── 深色主题：玻璃面板 ─────────
val DarkGlassSurface = Color(0x33FFFFFF)    // 半透明白
val DarkGlassBorder = Color(0x44FFFFFF)
val DarkGlassBorderSoft = Color(0x22FFFFFF)

// ───────── 深色主题：文本 ─────────
val DarkTextPrimary = Color(0xFFE8EDF3)
val DarkTextSecondary = Color(0xFFA8B3C1)
val DarkTextTertiary = Color(0xFF6B7684)

// ───────── 强调色（两套主题通用） ─────────
val Accent = Color(0xFF0F8AA8)           // 主青
val AccentSoft = Color(0xFF7BD0DC)
val AccentDeep = Color(0xFF0A5C72)
val OnAccent = Color(0xFFFFFFFF)

// ───────── 状态色（两套主题通用） ─────────
val Success = Color(0xFF1FB892)
val Warning = Color(0xFFE9A23B)
val Danger = Color(0xFFE85A6A)

// ───────── 功能色（用于卡片图标，两套主题通用） ─────────
val StatTeal = Color(0xFF149A9A)
val StatIndigo = Color(0xFF6F69C8)
val StatCoral = Color(0xFFE37A55)
val StatRose = Color(0xFFE05B73)
val StatAmber = Color(0xFFE9A23B)
val StatSky = Color(0xFF2D8FE0)

// ───────── 时间线类型色（两套主题通用） ─────────
val TimelineAi = Color(0xFF7C5BD9)
val TimelineTask = Color(0xFF0F8AA8)
val TimelineDiary = Color(0xFFE9A23B)
val TimelineReminder = Color(0xFFE37A55)

// ───────── 兼容旧组件的别名（不再主动使用，但避免残留引用编译失败） ─────────
val Background = WaterTop
val Surface = GlassSurface
val SurfaceVariant = Color(0xFFF6FAFB)
val AccentDim = AccentDeep
val GradientTop = WaterTop
val GradientMid = WaterMid
val GradientBottom = WaterBottom
