package com.orka.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orka.core.common.TimeFormatter
import com.orka.core.common.UrgencyTier
import com.orka.core.common.UrgencyCalculator
import com.orka.core.model.ActionEmphasis
import com.orka.core.model.Task
import java.time.Duration
import java.time.Instant

private val DarkColors = darkColorScheme(
    primary = OrkaButtonBlue,
    onPrimary = OrkaTextPrimary,
    background = OrkaDarkBackground,
    surface = OrkaDarkSurface,
    onSurface = OrkaTextPrimary,
    surfaceVariant = OrkaDarkSurfaceRaised,
    onSurfaceVariant = OrkaTextSecondary,
    error = OrkaRed,
)

private val LightColors = lightColorScheme(
    primary = OrkaLightPrimary,
    onPrimary = Color.White,
    background = OrkaLightBackground,
    surface = OrkaLightSurface,
    onSurface = OrkaLightTextPrimary,
    surfaceVariant = OrkaLightSurfaceRaised,
    onSurfaceVariant = OrkaLightTextSecondary,
    error = OrkaRed,
)

object OrkaSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

@Composable
fun OrkaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OrkaTypography,
        content = content,
    )
}

@Composable
fun OrkaSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun OrkaScreenContainer(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = OrkaSpacing.md, vertical = OrkaSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(OrkaSpacing.md),
        content = content,
    )
}

@Composable
fun OrkaActionButton(
    text: String,
    emphasis: ActionEmphasis,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = when (emphasis) {
        ActionEmphasis.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = OrkaButtonBlue,
            contentColor = OrkaTextPrimary,
        )
        ActionEmphasis.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = OrkaDarkSurfaceRaised,
            contentColor = OrkaTextPrimary,
        )
        ActionEmphasis.TERTIARY -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = OrkaTextSecondary,
        )
    }

    Button(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (emphasis == ActionEmphasis.PRIMARY) 56.dp else 52.dp),
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = colors,
    ) {
        Text(
            text = text,
            style = if (emphasis == ActionEmphasis.PRIMARY) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.bodyLarge
            },
        )
    }
}

@Composable
fun OrkaTaskCard(
    task: Task,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
    onClick: () -> Unit = {},
) {
    val tier = UrgencyCalculator.tier(task.deadline, now)
    val accent = urgencyColor(tier)
    val timeLeft = Duration.between(now, task.deadline)

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .padding(start = OrkaSpacing.sm)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(accent)
                    .width(3.dp)
                    .height(84.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OrkaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(OrkaSpacing.xs),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryPill(task.category.name.lowercase().replaceFirstChar(Char::titlecase), accent = categoryColor(task.category))
                    Text(
                        text = if (timeLeft.isNegative) {
                            "OVERDUE ${TimeFormatter.humanizeDuration(timeLeft.abs())}"
                        } else {
                            "due in ${TimeFormatter.humanizeDuration(timeLeft)}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (tier == UrgencyTier.CRITICAL) OrkaRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
fun AlarmBackground(
    tier: UrgencyTier,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val brush = when (tier) {
        UrgencyTier.CALM -> Brush.verticalGradient(listOf(Color(0x1600D4FF), OrkaDarkBackground))
        UrgencyTier.URGENT -> Brush.verticalGradient(listOf(Color(0x1AFFB703), Color(0xFF0A0700)))
        UrgencyTier.CRITICAL -> Brush.verticalGradient(listOf(Color(0x22E63946), Color(0xFF090202)))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush),
        content = content,
    )
}
