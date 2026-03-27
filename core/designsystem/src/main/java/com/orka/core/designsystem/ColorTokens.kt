package com.orka.core.designsystem

import androidx.compose.ui.graphics.Color
import com.orka.core.common.UrgencyTier
import com.orka.core.model.TaskCategory

val OrkaDarkBackground = Color(0xFF050505)
val OrkaDarkSurface = Color(0xFF0F0F0F)
val OrkaDarkSurfaceRaised = Color(0xFF161616)
val OrkaDarkOverlay = Color(0xFF1E1E1E)
val OrkaDivider = Color(0xFF1F1F1F)
val OrkaBlue = Color(0xFF3A86FF)
val OrkaButtonBlue = Color(0xFF2B6FE0)
val OrkaCyan = Color(0xFF00D4FF)
val OrkaAmber = Color(0xFFFFB703)
val OrkaRed = Color(0xFFE63946)
val OrkaSuccess = Color(0xFF2A9D8F)
val OrkaTextPrimary = Color(0xFFF5F5F5)
val OrkaTextSecondary = Color(0xFFA0A0A0)
val OrkaTextDisabled = Color(0xFF4A4A4A)

val OrkaLightBackground = Color(0xFFFAFAFA)
val OrkaLightSurface = Color(0xFFFFFFFF)
val OrkaLightSurfaceRaised = Color(0xFFF1F3F5)
val OrkaLightPrimary = Color(0xFF1D57E8)
val OrkaLightTextPrimary = Color(0xFF0B0B0B)
val OrkaLightTextSecondary = Color(0xFF555555)

fun urgencyColor(tier: UrgencyTier): Color = when (tier) {
    UrgencyTier.CALM -> OrkaBlue
    UrgencyTier.URGENT -> OrkaAmber
    UrgencyTier.CRITICAL -> OrkaRed
}

fun categoryColor(category: TaskCategory): Color = when (category) {
    TaskCategory.ACADEMIC -> OrkaBlue
    TaskCategory.PERSONAL -> Color(0xFF7CC6FE)
    TaskCategory.PROFESSIONAL -> Color(0xFF2A9D8F)
    TaskCategory.CLUB -> Color(0xFF8D6BFF)
    TaskCategory.HEALTH -> Color(0xFF6BCB77)
    TaskCategory.FINANCIAL -> Color(0xFFFFC145)
    TaskCategory.OTHER -> OrkaTextSecondary
}
