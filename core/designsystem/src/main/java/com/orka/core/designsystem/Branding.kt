package com.orka.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun OrkaWordmark(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.orka_wordmark),
        contentDescription = "ORKA",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun OrkaMark(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.orka_mark),
        contentDescription = "ORKA mark",
        modifier = modifier.size(64.dp),
        contentScale = ContentScale.Fit,
    )
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
