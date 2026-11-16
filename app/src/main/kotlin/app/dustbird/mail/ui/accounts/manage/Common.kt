package app.dustbird.mail.ui.accounts.manage

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.ui.theme.accountAccent

@Composable
fun Section(@StringRes title: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ChoiceGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    ButtonGroup(
        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(menuState = it) },
        modifier = modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            toggleableItem(checked = value == selected, label = label, onCheckedChange = { if (it) onSelect(value) })
        }
    }
}

@Composable
fun ConfirmDialog(
    @StringRes title: Int,
    text: String,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(stringResource(confirm), fontWeight = if (destructive) FontWeight.Bold else null) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_cancel)) } },
    )
}

@Composable
fun FormField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    error: String? = null,
    support: String? = null,
) {
    val message = error ?: support
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = error != null,
        supportingText = if (message == null) null else { { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
fun StageList(stages: ConnectionStages, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        stages.rows().forEach { (label, state) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(state.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (state == StageState.UNKNOWN) FontWeight.Normal else FontWeight.Bold,
                    color = when (state) {
                        StageState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        StageState.OK -> MaterialTheme.colorScheme.primary
                        StageState.FAILED -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
fun AccentLabel(text: String, accentIndex: Int?, modifier: Modifier = Modifier) {
    if (accentIndex == null) {
        Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val accent = accountAccent(accentIndex)
        Text(
            text,
            modifier.background(accent.container, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            color = accent.content,
        )
    }
}

@Composable
fun MoveButtons(
    upDescription: String,
    downDescription: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, upDescription) }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, downDescription) }
    }
}
