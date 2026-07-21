package com.orka.core.designsystem

import androidx.compose.foundation.Image
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

// The orka_wordmark/orka_mark PNGs have an opaque dark-navy background baked in (no alpha
// channel) — on the Light theme they'd otherwise render as a jarring dark rectangle. Wrapping
// them in a plate of this same color makes the logo read as an intentional badge in both themes
// instead of a mismatched image edge.
private val OrkaBrandPlate = Color(0xFF0A0D1B)

@Composable
fun OrkaWordmark(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = OrkaBrandPlate,
        shape = RoundedCornerShape(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.orka_wordmark),
            contentDescription = "ORKA",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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

@Composable
fun OrkaEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
