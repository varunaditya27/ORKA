package com.orka.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The orka_wordmark/orka_mark PNGs have an opaque dark-navy background baked in (no alpha
// channel) — on the Light theme they'd otherwise render as a jarring dark rectangle. Wrapping
// them in a plate of this same color makes the logo read as an intentional badge in both themes
// instead of a mismatched image edge.
private val OrkaBrandPlate = Color(0xFF0A0D1B)

// orka_wordmark.png is 1536x1024 — callers that size this via height alone (rather than also
// fixing width) need this to derive a correctly-proportioned width, or the image either
// overflows or gets squeezed by the surrounding Surface's own layout.
const val ORKA_WORDMARK_ASPECT_RATIO = 1536f / 1024f

@Composable
fun OrkaWordmark(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    Surface(
        modifier = modifier,
        color = OrkaBrandPlate,
        shape = RoundedCornerShape(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.orka_wordmark),
            contentDescription = "ORKA",
            modifier = Modifier.padding(contentPadding),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun OrkaMark(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = OrkaBrandPlate,
        shape = RoundedCornerShape(16.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.orka_mark),
            contentDescription = "ORKA mark",
            modifier = Modifier.size(64.dp).padding(8.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * A small "kicker" label. Uppercased with letter-spacing so it visually reads as a distinct
 * category tag rather than body text — e.g. Alarm's "ORKA" label, TaskDetail's "Task" tag
 * next to its back control.
 */
@Composable
fun OrkaEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Standard header for the 5 bottom-nav screens (Capture/Tasks/Archive/Settings/Diagnostics):
 * the compact brand wordmark, then the screen's own title below it. Replaces an earlier pattern
 * where each screen showed an `OrkaEyebrow` directly above a headline repeating the same word
 * (e.g. eyebrow "Tasks" above headline "Tasks") — meant to read as a kicker+title pair, but in
 * practice just looked like duplicated text.
 */
@Composable
fun OrkaScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OrkaSpacing.sm)) {
        OrkaWordmark(
            modifier = Modifier
                .height(56.dp)
                .aspectRatio(ORKA_WORDMARK_ASPECT_RATIO),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        )
        Text(title, style = MaterialTheme.typography.headlineLarge)
    }
}
