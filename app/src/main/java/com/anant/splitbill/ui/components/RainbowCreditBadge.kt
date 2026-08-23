package com.anant.splitbill.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anant.splitbill.R

private val rainbowAnimationSpec = infiniteRepeatable<Float>(
    animation = tween(durationMillis = 2800, easing = LinearEasing),
    repeatMode = RepeatMode.Restart
)

@Composable
private fun rememberCyclingHue(): Float {
    val transition = rememberInfiniteTransition(label = "createdByRainbow")
    val hue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = rainbowAnimationSpec,
        label = "hue"
    )
    return hue
}

private fun hsvRainbow(hue: Float): Color =
    Color.hsv(hue = ((hue % 360f) + 360f) % 360f, saturation = 0.85f, value = 0.95f)

private fun cyclingRainbowText(text: String, hue: Float): AnnotatedString {
    val stepDegrees = 360f / text.length.coerceAtLeast(1)
    return buildAnnotatedString {
        text.forEachIndexed { index, char ->
            withStyle(SpanStyle(color = hsvRainbow(hue + index * stepDegrees))) {
                append(char)
            }
        }
    }
}

private fun rainbowBorderBrush(hue: Float): Brush {
    val stops = 8
    val colors = List(stops + 1) { i ->
        hsvRainbow(hue + i * (360f / stops))
    }
    return Brush.sweepGradient(colors = colors)
}

@Composable
fun RainbowCreditBadge(name: String, onClick: () -> Unit) {
    val hue = rememberCyclingHue()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(
                width = 1.5.dp,
                brush = rainbowBorderBrush(hue),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = cyclingRainbowText(name, hue),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(4.dp))
        PartyParrot()
    }
}

@Composable
private fun PartyParrot(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                contentDescription = "Party parrot"
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                val drawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.resources, R.raw.party_parrot)
                )
                setImageDrawable(drawable)
                if (drawable is Animatable) drawable.start()
            }
        },
        modifier = modifier.size(20.dp)
    )
}
