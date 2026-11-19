package app.dustbird.mail.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.ui.theme.FlatCard

@Composable
fun AgentConsentCard(onAgree: () -> Unit, modifier: Modifier = Modifier) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    FlatCard(modifier, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_consent_needed), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.agent_consent_text), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it }),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = agreed, onCheckedChange = null)
                Text(stringResource(R.string.agent_consent_title), style = MaterialTheme.typography.bodyLarge)
            }
            Button(onClick = onAgree, enabled = agreed) { Text(stringResource(R.string.agent_consent_agree)) }
        }
    }
}
