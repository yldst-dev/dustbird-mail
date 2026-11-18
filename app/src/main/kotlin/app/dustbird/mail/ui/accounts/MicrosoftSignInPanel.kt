package app.dustbird.mail.ui.accounts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.dustbird.mail.R
import app.dustbird.mail.data.secure.SignInFailure
import app.dustbird.mail.data.secure.SignInState
import app.dustbird.mail.ui.accounts.manage.Waiting
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.pillFieldColors

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MicrosoftSignInPanel(clientId: TextFieldState, state: SignInState, onStart: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val busy = state is SignInState.Starting || state is SignInState.Code
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(
            state = clientId,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text(stringResource(R.string.microsoft_client_id)) },
            supportingText = { Text(stringResource(R.string.microsoft_client_id_hint)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = Pill,
            colors = pillFieldColors(),
        )
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SignInState.Starting -> Waiting(R.string.microsoft_sign_in_starting)
                is SignInState.Code -> {
                    Text(stringResource(R.string.microsoft_sign_in_code_title), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(state.userCode, style = MaterialTheme.typography.headlineMediumEmphasized, fontFamily = FontFamily.Monospace)
                    }
                    Text(stringResource(R.string.microsoft_sign_in_code_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SignInState.Done -> Text(stringResource(R.string.microsoft_sign_in_done), style = MaterialTheme.typography.bodyLargeEmphasized)
                is SignInState.Failed -> Text(
                    stringResource(
                        when (state.failure) {
                            SignInFailure.CLIENT -> R.string.microsoft_sign_in_failed_client
                            SignInFailure.EXPIRED -> R.string.microsoft_sign_in_failed_expired
                            SignInFailure.DECLINED -> R.string.microsoft_sign_in_failed_declined
                            SignInFailure.NETWORK -> R.string.microsoft_sign_in_failed_network
                        },
                    ),
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = MaterialTheme.colorScheme.error,
                )
                SignInState.Idle -> Unit
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state) {
                is SignInState.Code -> {
                    Button(onClick = {
                        copyCode(context, state.userCode)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, state.url.toUri())) }
                    }) { Text(stringResource(R.string.microsoft_sign_in_open)) }
                    OutlinedButton(onClick = { copyCode(context, state.userCode) }) { Text(stringResource(R.string.microsoft_sign_in_copy)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.microsoft_sign_in_cancel)) }
                }
                SignInState.Starting -> TextButton(onClick = onCancel) { Text(stringResource(R.string.microsoft_sign_in_cancel)) }
                SignInState.Done -> OutlinedButton(onClick = onStart) { Text(stringResource(R.string.microsoft_sign_in_again)) }
                else -> Button(onClick = onStart, enabled = clientId.text.isNotBlank()) { Text(stringResource(R.string.microsoft_sign_in_start)) }
            }
        }
    }
}

private fun copyCode(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
}
