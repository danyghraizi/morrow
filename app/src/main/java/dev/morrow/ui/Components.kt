package dev.morrow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.morrow.data.Item
import dev.morrow.domain.Fmt
import dev.morrow.R
import dev.morrow.domain.Insight
import java.time.ZonedDateTime

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MorrowCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { content() }
}

/** The one interactive control that appears everywhere: a round check. */
@Composable
fun CheckCircle(done: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2f - 1f
            if (done) {
                drawCircle(accent, radius = r)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.34f)
                }
                drawPath(path, Color.White, style = Stroke(width = 2.2f.dp.toPx(), cap = StrokeCap.Round))
            } else {
                drawCircle(line, radius = r, style = Stroke(width = 1.6f.dp.toPx()))
            }
        }
    }
}

@Composable
fun TimeColumn(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.width(52.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontFeatureSettings = "tnum",
            fontFamily = FontFamily.Monospace,
        ),
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

@Composable
fun ItemRow(
    item: Item,
    now: ZonedDateTime,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    showDay: Boolean = false,
) {
    val copy = dev.morrow.domain.Copy.current()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.id <= 0L) {
            CalendarDot()
        } else {
            CheckCircle(done = item.done, onClick = onToggle)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val subtitle = buildString {
                when {
                    item.allDay -> append(copy.allDay())
                    item.isAnytime -> append(copy.anytime())
                    showDay -> append(Fmt.whenText(item.start, now))
                    else -> append(Fmt.relative(item.start, now))
                }
                when {
                    item.id <= 0L -> append(" · " + copy.fromCalendar())
                    item.isEvent -> append(" · " + if (item.calendarEventId != null) copy.inCalendar() else copy.eventLabel())
                }
                item.rrule?.let { rule -> copy.repeatLabel(rule)?.let { append(" · $it") } }
                if (item.engine == "model") append(" · " + copy.onDevice())
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!item.allDay && item.start > 0 && !item.isAnytime) {
            Spacer(Modifier.width(8.dp))
            if (showDay) {
                TimeColumn(Fmt.time(item.start, now.zone), muted = item.done)
            } else {
                Text(
                    Fmt.time(item.start, now.zone),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFeatureSettings = "tnum",
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun InsightCard(insight: Insight, onAct: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(insight.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (insight.action !is dev.morrow.domain.InsightAction.None) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.action_do_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onAct)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
fun EngineBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
fun MarkGlyph(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val r = size.minDimension * 0.32f
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(size.width / 2 - r, size.height / 2 - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = size.minDimension * 0.085f, cap = StrokeCap.Round),
        )
        drawCircle(color, radius = size.minDimension * 0.105f, center = Offset(size.width / 2, size.height / 2))
    }
}

/** Read-only marker for rows that belong to the system calendar. */
@Composable
private fun CalendarDot() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(color, radius = size.minDimension / 2f - 1f.dp.toPx(), style = Stroke(1.6.dp.toPx()))
            drawCircle(color, radius = size.minDimension * 0.22f)
        }
    }
}

@Composable
fun SettingRow(title: String, detail: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
