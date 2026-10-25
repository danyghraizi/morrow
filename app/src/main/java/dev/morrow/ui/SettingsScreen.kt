package dev.morrow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.morrow.R
import dev.morrow.calendar.CalendarInfo
import dev.morrow.data.Memory
import dev.morrow.domain.Fmt
import dev.morrow.model.ModelPack
import java.time.ZonedDateTime
import java.util.Locale

data class SettingsUi(
    val useModel: Boolean = true,
    val modelLoaded: Boolean = false,
    val engineBusy: Boolean = false,
    val lastLatencyMs: Long = 0L,
    val engineError: String? = null,
    val calendarId: Long = -1L,
    val calendars: List<CalendarInfo> = emptyList(),
    val calendarPermission: Boolean = false,
    val leadMinutes: Int = 10,
    val exactAlarms: Boolean = false,
    val memories: List<Memory> = emptyList(),
    val itemCount: Int = 0,
    val doneCount: Int = 0,
    val showCalendar: Boolean = true,
    val externalCount: Int = 0,
)

@Composable
fun SettingsScreen(
    ui: SettingsUi,
    contentPadding: PaddingValues,
    onUseModel: (Boolean) -> Unit,
    onReleaseMemory: () -> Unit,
    onToggleCalendar: () -> Unit,
    onCalendar: (Long) -> Unit,
    onLead: (Int) -> Unit,
    onExactAlarms: () -> Unit,
    onForget: (Long) -> Unit,
    onClearHistory: () -> Unit,
    onResetTutorial: () -> Unit,
    onShowCalendar: (Boolean) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionLabel(stringResource(R.string.section_model)) }
        item {
            MorrowCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow(
                        title = ModelPack.Info.TITLE,
                        detail = "${ModelPack.Info.QUANT} · ${ModelPack.Info.BYTES / (1L shl 20)} MB",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingRow(
                        title = stringResource(R.string.settings_use_model),
                        detail = stringResource(R.string.settings_use_model_detail),
                        trailing = {
                            Switch(checked = ui.useModel, onCheckedChange = onUseModel)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingRow(
                        title = stringResource(R.string.settings_in_memory),
                        detail = when {
                            ui.engineError != null -> ui.engineError
                            ui.engineBusy -> stringResource(R.string.settings_memory_loading)
                            ui.modelLoaded -> if (ui.lastLatencyMs > 0) {
                                stringResource(R.string.settings_memory_latency, ui.lastLatencyMs.toInt())
                            } else stringResource(R.string.settings_memory_loaded)
                            else -> stringResource(R.string.settings_memory_unloaded)
                        },
                        trailing = {
                            TextButton(onClick = onReleaseMemory, enabled = ui.modelLoaded) {
                                Text(stringResource(R.string.settings_free))
                            }
                        },
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.section_calendar)) }
        item {
            MorrowCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_write_events),
                        detail = if (!ui.calendarPermission) {
                            stringResource(R.string.settings_calendar_permission)
                        } else stringResource(R.string.settings_write_events_detail),
                        trailing = {
                            if (!ui.calendarPermission) {
                                TextButton(onClick = onToggleCalendar) { Text(stringResource(R.string.action_allow)) }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingRow(
                        title = stringResource(R.string.settings_show_calendar),
                        detail = if (ui.calendarPermission) {
                            pluralStringResource(
                                R.plurals.settings_show_calendar_detail, ui.externalCount, ui.externalCount,
                            )
                        } else {
                            stringResource(R.string.settings_calendar_permission)
                        },
                        trailing = {
                            Switch(
                                checked = ui.showCalendar && ui.calendarPermission,
                                onCheckedChange = onShowCalendar,
                                enabled = ui.calendarPermission,
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    CalendarOption(
                        name = stringResource(R.string.settings_local_only),
                        detail = stringResource(R.string.settings_local_only_detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = ui.calendarId <= 0,
                        onClick = { onCalendar(-1L) },
                    )
                    ui.calendars.forEach { calendar ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        CalendarOption(
                            name = calendar.name,
                            detail = calendar.account,
                            color = Color(calendar.color),
                            selected = ui.calendarId == calendar.id,
                            onClick = { onCalendar(calendar.id) },
                        )
                    }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.section_reminders)) }
        item {
            MorrowCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow(title = stringResource(R.string.settings_nudge_before))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 5, 10, 30).forEach { minutes ->
                            FilterChip(
                                selected = ui.leadMinutes == minutes,
                                onClick = { onLead(minutes) },
                                label = {
                                    Text(
                                        if (minutes == 0) stringResource(R.string.settings_on_time)
                                        else stringResource(R.string.settings_minutes, minutes),
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingRow(
                        title = stringResource(R.string.settings_exact),
                        detail = if (ui.exactAlarms) {
                            stringResource(R.string.settings_exact_ok)
                        } else stringResource(R.string.settings_exact_no),
                        trailing = {
                            if (!ui.exactAlarms) TextButton(onClick = onExactAlarms) { Text(stringResource(R.string.action_allow)) }
                        },
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.section_remembered)) }
        item {
            MorrowCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (ui.memories.isEmpty()) {
                        SettingRow(
                            title = stringResource(R.string.settings_nothing_yet),
                            detail = stringResource(R.string.settings_nothing_yet_detail),
                        )
                    }
                    ui.memories.forEach { memory ->
                        SettingRow(
                            title = memory.text,
                            detail = Fmt.dayLabel(memory.createdAt, ZonedDateTime.now()),
                            trailing = {
                                TextButton(onClick = { onForget(memory.id) }) { Text(stringResource(R.string.action_forget)) }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_chat_history),
                        detail = stringResource(R.string.settings_chat_history_detail, ui.itemCount, ui.doneCount),
                        trailing = { TextButton(onClick = onClearHistory) { Text(stringResource(R.string.action_clear)) } },
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.section_about)) }
        item {
            MorrowCard {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            R.string.settings_about_model,
                            ModelPack.Info.TITLE, ModelPack.Info.SOURCE, ModelPack.Info.LICENSE,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onResetTutorial) { Text(stringResource(R.string.settings_show_welcome)) }
                }
            }
        }
    }
}

@Composable
private fun CalendarOption(name: String, detail: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (selected) "●" else "○",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
