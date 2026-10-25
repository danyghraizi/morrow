package dev.morrow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morrow.data.Item
import dev.morrow.domain.Fmt
import dev.morrow.domain.Insight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.morrow.R
import dev.morrow.domain.Insights
import java.time.ZonedDateTime

/**
 * The day, in the order it will happen. One hero for what is next, then the
 * timeline, then at most three suggestions.
 */
@Composable
fun TodayScreen(
    items: List<Item>,
    now: ZonedDateTime,
    contentPadding: PaddingValues,
    onToggle: (Item) -> Unit,
    onOpen: (Item) -> Unit,
    onInsight: (Insight) -> Unit,
    onAsk: () -> Unit,
) {
    val zone = now.zone
    val nowMs = now.toInstant().toEpochMilli()
    val startOfDay = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endOfTomorrow = now.toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

    val open = items.filter { !it.done }
    val overdue = open.filter { it.start in 1 until nowMs }
    val today = open.filter { it.start in nowMs..<endOfDay && it.start >= nowMs }
    val tomorrow = open.filter { it.start in endOfDay..<endOfTomorrow }
    val later = open.filter { it.start >= endOfTomorrow }
    val anytime = open.filter { it.isAnytime }
    val doneToday = items.filter { it.done && (it.doneAt ?: 0) in startOfDay..<endOfDay }
    val next = open.filter { it.start >= nowMs }.minByOrNull { it.start }
    val insights = Insights.forDay(items, now)

    val summaryHead = if (today.isEmpty()) {
        stringResource(R.string.today_none_left)
    } else {
        pluralStringResource(R.plurals.today_ahead, today.size, today.size)
    }
    val slippedPart = if (overdue.isEmpty()) "" else
        " · " + pluralStringResource(R.plurals.today_slipped, overdue.size, overdue.size)
    val anytimePart = if (anytime.isEmpty()) "" else
        " · " + pluralStringResource(R.plurals.today_anytime, anytime.size, anytime.size)
    val summary = summaryHead + slippedPart + anytimePart

    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Fmt.fullDate(now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                MarkGlyph(Modifier.size(38.dp), MaterialTheme.colorScheme.primary)
            }
        }

        if (next != null) {
            item {
                Spacer(Modifier.height(20.dp))
                NextCard(
                    item = next,
                    now = now,
                    onDone = { onToggle(next) },
                    onOpen = { onOpen(next) },
                )
            }
        }

        if (insights.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_worth_doing)) }
            items(insights.size) { index ->
                val insight = insights[index]
                Box(Modifier.padding(bottom = 10.dp)) {
                    InsightCard(insight) { onInsight(insight) }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onAsk),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.today_tell_me),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (overdue.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_slipped), trailing = "${overdue.size}") }
            items(overdue.size) { i ->
                ItemRow(overdue[i], now, onToggle = { onToggle(overdue[i]) }, onClick = { onOpen(overdue[i]) })
            }
        }

        if (today.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_today), trailing = "${today.size}") }
            items(today.size) { i ->
                ItemRow(today[i], now, onToggle = { onToggle(today[i]) }, onClick = { onOpen(today[i]) })
            }
        }

        if (tomorrow.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_tomorrow)) }
            items(tomorrow.size) { i ->
                ItemRow(tomorrow[i], now, showDay = true, onToggle = { onToggle(tomorrow[i]) }, onClick = { onOpen(tomorrow[i]) })
            }
        }

        if (later.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_later)) }
            items(later.size) { i ->
                ItemRow(later[i], now, showDay = true, onToggle = { onToggle(later[i]) }, onClick = { onOpen(later[i]) })
            }
        }

        if (anytime.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_anytime)) }
            items(anytime.size) { i ->
                ItemRow(anytime[i], now, onToggle = { onToggle(anytime[i]) }, onClick = { onOpen(anytime[i]) })
            }
        }

        if (doneToday.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.section_done_today), trailing = "${doneToday.size}") }
            items(doneToday.size) { i ->
                ItemRow(doneToday[i], now, onToggle = { onToggle(doneToday[i]) }, onClick = { onOpen(doneToday[i]) })
            }
        }

        if (open.isEmpty() && doneToday.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.empty_title),
                    stringResource(R.string.empty_body),
                )
            }
        }
    }
}

@Composable
private fun NextCard(item: Item, now: ZonedDateTime, onDone: () -> Unit, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.today_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (item.isAnytime) dev.morrow.domain.Copy.current().anytime() else Fmt.relative(item.start, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.start > 0) {
                    Text(
                        Fmt.whenText(item.start, now),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.id > 0L) TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
            }
        }
    }
}
