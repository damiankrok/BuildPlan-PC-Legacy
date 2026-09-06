package com.buildplan.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/** Material 3 defaults with a few weight and tracking adjustments. */
internal val BuildPlanTypography = Default.copy(
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelSmall = Default.labelSmall.copy(letterSpacing = 0.8.sp),
)
