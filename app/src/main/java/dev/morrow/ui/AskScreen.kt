package dev.morrow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.morrow.R
import dev.morrow.data.Turn

private val OPENER_KEYS = listOf(
    R.string.ask_opener_1,
    R.string.ask_opener_2,
    R.string.ask_opener_3,
    R.string.ask_opener_4,
)

@Composable
fun AskScreen(
    turns: List<Turn>,
    thinking: Boolean,
    contentPadding: PaddingValues,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val dayUtterance = stringResource(R.string.ask_send_day)
    val suggestUtterance = stringResource(R.string.ask_send_suggest)
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size, thinking) {
        if (turns.isNotEmpty()) {
            // Let the new row lay out first, otherwise the scroll is clamped to
            // the previous item count and the newest reply stays off-screen.
            kotlinx.coroutines.delay(40)
            listState.animateScrollToItem(turns.lastIndex)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || thinking) return
        draft = ""
        onSend(trimmed)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 24.dp)) {
                        Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ask_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(OPENER_KEYS) { openerKey ->
                    val opener = stringResource(openerKey)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { send(opener) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            opener,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(turns, key = { it.id }) { turn ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Text(
                                turn.user,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(turn.reply, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EngineBadge(
                                    stringResource(
                                        when (turn.engine) {
                                            "rules" -> R.string.engine_rules
                                            "model" -> R.string.engine_model
                                            "model-offline" -> R.string.engine_off
                                            else -> R.string.engine_app
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (thinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ask_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp, top = 4.dp),
            ) {
                if (turns.isNotEmpty() && draft.isEmpty() && !thinking) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { send(dayUtterance) }) {
                            Text(stringResource(R.string.ask_chip_day))
                        }
                        TextButton(onClick = { send(suggestUtterance) }) {
                            Text(stringResource(R.string.ask_chip_suggest))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.ask_hint)) },
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(draft) }),
                    )
                    Spacer(Modifier.width(10.dp))
                    // While the model runs, the same control becomes a stop button:
                    // a local 270M model is fast, but let people bail out anyway.
                    val canSend = draft.isNotBlank() && !thinking
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                when {
                                    thinking -> MaterialTheme.colorScheme.surfaceVariant
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .clickable(enabled = thinking || canSend) { if (thinking) onStop() else send(draft) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (thinking) "■" else "↑",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
