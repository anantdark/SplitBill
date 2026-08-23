package com.anant.splitbill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.analytics.MemberUsage
import com.anant.splitbill.data.analytics.UsagePoint
import kotlin.math.max

private val MemberBarPalette = listOf(
    Color(0xFF0F6B5C),
    Color(0xFF8B5E00),
    Color(0xFF3D5A80),
    Color(0xFF9A3412),
    Color(0xFF5B4B8A),
    Color(0xFF0E7490),
)

@Composable
fun UsageChartCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
            } else {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun MonthlyUsageBarChart(
    points: List<UsagePoint>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.isEmpty() || points.all { it.units <= 0.0 }) {
        ChartEmptyState("No usage logged in the last few months.")
        return
    }

    val maxValue = max(points.maxOf { it.units }, 1.0).toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            val labelHeight = 28.dp.toPx()
            val chartHeight = size.height - labelHeight
            val barGap = 8.dp.toPx()
            val barWidth = ((size.width - barGap * (points.size + 1)) / points.size).coerceAtLeast(12.dp.toPx())

            points.forEachIndexed { index, point ->
                val fraction = (point.units / maxValue).toFloat().coerceIn(0f, 1f)
                val barHeight = chartHeight * fraction
                val left = barGap + index * (barWidth + barGap)
                val top = chartHeight - barHeight
                drawRoundRect(
                    color = barColor.copy(alpha = if (point.units > 0) 1f else 0.25f),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, max(barHeight, if (point.units > 0) 4.dp.toPx() else 0f)),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.forEach { point ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (point.units > 0) {
                        Text(
                            text = formatUnits(point.units),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemberUsageBarChart(
    members: List<MemberUsage>,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty() || members.all { it.units <= 0.0 }) {
        ChartEmptyState("No per-member usage yet.")
        return
    }

    val maxValue = max(members.maxOf { it.units }, 1.0).toFloat()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        members.forEachIndexed { index, member ->
            val color = MemberBarPalette[index % MemberBarPalette.size]
            val fraction = (member.units / maxValue).toFloat().coerceIn(0.05f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(72.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                ) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.18f),
                        size = size,
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    )
                    drawRoundRect(
                        color = color,
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    )
                }
                Text(
                    text = formatUnits(member.units),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .width(52.dp)
                        .padding(start = 8.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ChartEmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

private fun formatUnits(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.1f".format(value)
