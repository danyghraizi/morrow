package dev.morrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.morrow.R
import dev.morrow.model.ModelPack

/**
 * First run. The model is already inside the app, so this is only about
 * unpacking it once and asking for the two permissions that matter.
 */
@Composable
fun SetupScreen(
    state: ModelPack.State,
    onRetry: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarkGlyph(Modifier.size(72.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(26.dp))
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(18.dp)) {
                when (state) {
                    is ModelPack.State.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(R.string.setup_model_ready), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.setup_model_detail, ModelPack.Info.TITLE, (state.bytes / (1L shl 20)).toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    is ModelPack.State.Failed -> {
                        Text(stringResource(R.string.setup_failed), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.setup_retry)) }
                    }
                    is ModelPack.State.Unpacking -> {
                        Text(stringResource(R.string.setup_unpacking), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.setup_unpacking_detail, (state.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                    ModelPack.State.Idle -> {
                        Text(stringResource(R.string.setup_preparing), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            enabled = state is ModelPack.State.Ready,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(stringResource(R.string.setup_start), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.setup_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
