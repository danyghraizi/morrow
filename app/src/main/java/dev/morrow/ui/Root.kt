package dev.morrow.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morrow.AppGraph
import dev.morrow.R
import androidx.compose.ui.res.stringResource
import dev.morrow.calendar.CalendarFeed
import dev.morrow.calendar.CalendarInfo
import dev.morrow.data.Item
import dev.morrow.data.Kind
import dev.morrow.domain.Fmt
import dev.morrow.domain.Insight
import dev.morrow.domain.InsightAction
import dev.morrow.llm.LlamaEngine
import dev.morrow.model.ModelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime

private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Today(R.string.tab_today, R.drawable.ic_today),
    Ask(R.string.tab_ask, R.drawable.ic_ask),
    Settings(R.string.tab_settings, R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorrowRoot(graph: AppGraph) {
    val snapshot by graph.repo.snapshot.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val modelState by graph.modelPack.state.collectAsStateWithLifecycle()
    val engineState by graph.engine.state.collectAsStateWithLifecycle()
    val latency by graph.engine.lastLatencyMs.collectAsStateWithLifecycle()
    val externalEvents by graph.calendarFeed.events.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var thinking by remember { mutableStateOf(false) }
    var sheetItem by remember { mutableStateOf<Item?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var calendarGranted by remember { mutableStateOf(graph.calendar.hasPermission()) }
    var exactAlarms by remember { mutableStateOf(graph.reminders.canScheduleExact()) }

    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(20_000)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> calendarGranted = result.values.all { it } }

    LaunchedEffect(modelState) {
        graph.engine.modelPath = (modelState as? ModelPack.State.Ready)?.path
    }
    LaunchedEffect(calendarGranted) {
        calendars = if (calendarGranted) withContext(Dispatchers.IO) { graph.calendar.calendars() } else emptyList()
        graph.calendarFeed.refresh()
    }
    // Calendar occurrences change outside the app; re-read them while visible.
    LaunchedEffect(settings.showCalendar, calendarGranted) {
        while (settings.showCalendar && calendarGranted) {
            graph.calendarFeed.refresh()
            delay(5 * 60_000L)
        }
        if (!settings.showCalendar) graph.calendarFeed.clear()
    }
    val timeline = remember(snapshot.items, externalEvents, settings.showCalendar) {
        CalendarFeed.merge(snapshot.items, externalEvents, settings.showCalendar)
    }
    // Permissions granted in system settings only become visible on resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                calendarGranted = graph.calendar.hasPermission()
                exactAlarms = graph.reminders.canScheduleExact()
                graph.calendarFeed.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val undoLabel = stringResource(R.string.action_undo)
    fun announce(message: String, allowUndo: Boolean) = scope.launch {
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = if (allowUndo) undoLabel else null,
            duration = SnackbarDuration.Short,
        )
        if (allowUndo && result == SnackbarResult.ActionPerformed) graph.repo.undoLast()
    }

    fun send(text: String) {
        if (thinking) return
        thinking = true
        scope.launch {
            try {
                val reply = graph.assistant.respond(text)
                // A new or moved event changes the calendar feed too; without this
                // the count and Today rows stay stale until the next resume.
                if (reply.mutated) graph.calendarFeed.refresh()
                announce(reply.text.lineSequence().first().take(70), reply.mutated)
            } finally {
                thinking = false
            }
        }
    }

    fun applyInsight(insight: Insight) {
        scope.launch {
            when (val action = insight.action) {
                is InsightAction.Schedule -> graph.repo.move(action.id, action.at, action.at)
                is InsightAction.MoveToToday -> {
                    val target = defaultSlot(now)
                    action.ids.forEach { graph.repo.move(it, target, target) }
                }
                is InsightAction.MoveToTomorrow -> {
                    val tomorrow = now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0))
                        .atZone(now.zone).toInstant().toEpochMilli()
                    graph.repo.move(action.id, tomorrow, tomorrow)
                }
                InsightAction.None -> return@launch
            }
            announce("Updated", allowUndo = true)
        }
    }

    if (!settings.onboarded) {
        SetupScreen(
            state = modelState,
            onRetry = { graph.modelPack.retry() },
            onStart = {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                graph.settings.onboarded = true
            },
        )
        return
    }

    val sheet = sheetItem
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                        label = { Text(stringResource(entry.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        val padding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())
        when (tab) {
            Tab.Today -> TodayScreen(
                items = timeline,
                now = now,
                contentPadding = padding,
                onToggle = { item ->
                    scope.launch {
                        graph.repo.setDone(item.id, !item.done)
                        if (!item.done) announce("\"${item.title}\" done", allowUndo = true)
                    }
                },
                onOpen = { item ->
                    if (item.id <= 0L) openInCalendar(context, item) else sheetItem = item
                },
                onInsight = ::applyInsight,
                onAsk = { tab = Tab.Ask },
            )

            Tab.Ask -> AskScreen(
                turns = snapshot.turns,
                thinking = thinking,
                contentPadding = padding,
                onSend = ::send,
                onStop = { graph.engine.cancel() },
            )

            Tab.Settings -> SettingsScreen(
                ui = SettingsUi(
                    useModel = settings.useModel,
                    modelLoaded = engineState is LlamaEngine.State.Ready,
                    engineBusy = engineState is LlamaEngine.State.Loading,
                    lastLatencyMs = latency,
                    engineError = (engineState as? LlamaEngine.State.Failed)?.message,
                    calendarId = settings.calendarId,
                    calendars = calendars,
                    calendarPermission = calendarGranted,
                    leadMinutes = settings.leadMinutes,
                    exactAlarms = exactAlarms,
                    memories = snapshot.memories,
                    itemCount = snapshot.items.size,
                    doneCount = snapshot.items.count { it.done },
                    showCalendar = settings.showCalendar,
                    externalCount = externalEvents.size,
                ),
                contentPadding = padding,
                onUseModel = { enabled ->
                    graph.settings.useModel = enabled
                    if (!enabled) graph.engine.release()
                },
                onReleaseMemory = { graph.engine.release() },
                onToggleCalendar = {
                    calendarPermission.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    )
                },
                onCalendar = {
                    graph.settings.calendarId = it
                    graph.calendarFeed.refresh()
                },
                onLead = { graph.settings.leadMinutes = it },
                onExactAlarms = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                    exactAlarms = graph.reminders.canScheduleExact()
                },
                onForget = { id -> scope.launch { graph.repo.forget(id) } },
                onClearHistory = { scope.launch { graph.repo.clearHistory() } },
                onResetTutorial = { graph.settings.onboarded = false },
                onShowCalendar = { graph.settings.showCalendar = it },
            )
        }
    }

    if (sheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetItem = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ItemSheet(
                item = sheet,
                now = now,
                calendarId = settings.calendarId,
                onClose = { sheetItem = null },
                onDone = { done ->
                    scope.launch {
                        graph.repo.setDone(sheet.id, done)
                        sheetItem = null
                    }
                },
                onMove = { at ->
                    scope.launch {
                        graph.repo.move(sheet.id, at, at)
                        graph.repo.itemById(sheet.id)?.takeIf { it.isEvent && it.calendarEventId != null }?.let { updated ->
                            withContext(Dispatchers.IO) { graph.calendar.update(updated) }
                        }
                        sheetItem = null
                    }
                },
                onToggleKind = {
                    scope.launch {
                        val target = if (sheet.isEvent) Kind.TASK else Kind.EVENT
                        val start = if (sheet.start == 0L) defaultSlot(now) else sheet.start
                        var updated = sheet.copy(
                            kind = target,
                            start = start,
                            end = if (target == Kind.EVENT) start + 3_600_000L else null,
                            reminderAt = if (target == Kind.EVENT) start - graph.settings.leadMinutes * 60_000L else start,
                        )
                        if (target == Kind.EVENT && graph.settings.calendarId > 0 && calendarGranted) {
                            val eventId = withContext(Dispatchers.IO) {
                                graph.calendar.insert(updated, graph.settings.calendarId)
                            }
                            if (eventId != null) updated = updated.copy(calendarEventId = eventId)
                        }
                        if (target == Kind.TASK) {
                            sheet.calendarEventId?.let { withContext(Dispatchers.IO) { graph.calendar.delete(it) } }
                            updated = updated.copy(calendarEventId = null)
                        }
                        graph.repo.update(updated)
                        graph.calendarFeed.refresh()
                        sheetItem = null
                    }
                },
                onToggleCalendar = {
                    scope.launch {
                        if (sheet.calendarEventId != null) {
                            withContext(Dispatchers.IO) { graph.calendar.delete(sheet.calendarEventId) }
                            graph.repo.update(sheet.copy(calendarEventId = null))
                        } else if (graph.settings.calendarId > 0 && calendarGranted) {
                            val eventId = withContext(Dispatchers.IO) {
                                graph.calendar.insert(sheet, graph.settings.calendarId)
                            }
                            if (eventId != null) graph.repo.update(sheet.copy(calendarEventId = eventId))
                        }
                        graph.calendarFeed.refresh()
                        sheetItem = null
                    }
                },
                onDelete = {
                    scope.launch {
                        sheet.calendarEventId?.let { withContext(Dispatchers.IO) { graph.calendar.delete(it) } }
                        graph.repo.delete(sheet.id)
                        graph.calendarFeed.refresh()
                        sheetItem = null
                    }
                },
            )
        }
    }
}

/** Opens the owning app at the event, rather than editing a row we do not own. */
private fun openInCalendar(context: android.content.Context, item: Item) {
    val eventId = item.calendarEventId ?: return
    val uri = android.content.ContentUris.withAppendedId(
        android.provider.CalendarContract.Events.CONTENT_URI, eventId,
    )
    val intent = Intent(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { android.util.Log.w("MorrowCalendar", "no calendar app for $uri: ${it.javaClass.simpleName}") }
}

/** 18:00 today if that is still ahead, otherwise 09:00 tomorrow. */
private fun defaultSlot(now: ZonedDateTime): Long {
    val evening = now.toLocalDate().atTime(LocalTime.of(18, 0)).atZone(now.zone)
    return (if (evening.isAfter(now.plusMinutes(30))) evening
    else now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(now.zone)).toInstant().toEpochMilli()
}

@Composable
private fun ItemSheet(
    item: Item,
    now: ZonedDateTime,
    calendarId: Long,
    onClose: () -> Unit,
    onDone: (Boolean) -> Unit,
    onMove: (Long) -> Unit,
    onToggleKind: () -> Unit,
    onToggleCalendar: () -> Unit,
    onDelete: () -> Unit,
) {
    fun at(dayOffset: Long, hour: Int): Long =
        now.toLocalDate().plusDays(dayOffset).atTime(LocalTime.of(hour, 0)).atZone(now.zone).toInstant().toEpochMilli()

    val daysToMonday = ((8 - now.dayOfWeek.value) % 7).let { if (it == 0) 7 else it }.toLong()

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding()) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        val copy = dev.morrow.domain.Copy.current()
        Text(
            buildString {
                append(
                    if (item.isAnytime) copy.anytime()
                    else "${if (item.isEvent) stringResource(R.string.sheet_event) else stringResource(R.string.sheet_task)} · ${Fmt.whenText(item.start, now)}"
                )
                item.rrule?.let { rule -> copy.repeatLabel(rule)?.let { append(" · $it") } }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        SheetAction(
            if (item.done) stringResource(R.string.sheet_mark_undone) else stringResource(R.string.sheet_mark_done)
        ) { onDone(!item.done) }
        SheetAction(stringResource(R.string.sheet_move_tomorrow)) { onMove(at(1, 9)) }
        SheetAction(stringResource(R.string.sheet_move_monday)) { onMove(at(daysToMonday, 9)) }
        SheetAction(
            if (item.isEvent) stringResource(R.string.sheet_make_task) else stringResource(R.string.sheet_make_event)
        ) { onToggleKind() }
        if (item.isEvent && calendarId > 0) {
            SheetAction(
                if (item.calendarEventId != null) stringResource(R.string.sheet_remove_calendar)
                else stringResource(R.string.sheet_add_calendar)
            ) { onToggleCalendar() }
        }
        SheetAction(stringResource(R.string.sheet_delete), destructive = true) { onDelete() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sheet_close))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SheetAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
