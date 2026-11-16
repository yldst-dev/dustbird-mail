package app.dustbird.mail.ui.accounts.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.identity.RuleResult
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.send.FailureCodes
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.repo.IdentityInput
import app.dustbird.mail.data.repo.IdentityResult
import app.dustbird.mail.ui.LocalAppContainer
import kotlinx.coroutines.launch

private const val NEW_IDENTITY = 0L

@Composable
fun IdentitiesSection(
    account: MailAccount,
    accentIndex: Int?,
    identities: List<MailIdentity>,
    appDefaultId: Long?,
    testSend: TestSend,
    onTestSend: (MailIdentity, String, String) -> Unit,
) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var testing by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val testSubject = stringResource(R.string.accounts_test_subject)
    val testBody = stringResource(R.string.accounts_test_body)

    fun move(index: Int, delta: Int) {
        val ids = identities.map { it.id }.toMutableList()
        val target = index + delta
        if (index !in ids.indices || target !in ids.indices) return
        ids.add(target, ids.removeAt(index))
        scope.launch { container.accounts.reorderIdentities(account.id, ids) }
    }

    Section(R.string.accounts_identities_heading) {
        Text(stringResource(R.string.accounts_identities_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        identities.forEachIndexed { index, identity ->
            if (index > 0) HorizontalDivider()
            IdentityRow(
                identity = identity,
                accentIndex = accentIndex,
                isAppDefault = identity.id == appDefaultId,
                canMoveUp = index > 0,
                canMoveDown = index < identities.lastIndex,
                testRunning = testSend == TestSend.Running,
                onEdit = { editing = identity.id },
                onDelete = { deleting = identity.id },
                onTest = { testing = identity.id },
                onMove = { move(index, it) },
                onSetAccountDefault = { scope.launch { container.accounts.setAccountDefaultIdentity(account.id, identity.id) } },
                onSetAppDefault = { scope.launch { container.accounts.setAppDefaultIdentity(identity.id) } },
            )
        }
        OutlinedButton(onClick = { editing = NEW_IDENTITY }) { Text(stringResource(R.string.accounts_identity_add)) }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
            TestSendStatus(testSend)
        }
    }

    editing?.let { id ->
        val target = identities.firstOrNull { it.id == id }
        if (id == NEW_IDENTITY || target != null) {
            IdentityEditor(account.id, target, onDismiss = { editing = null })
        }
    }

    deleting?.let { id ->
        identities.firstOrNull { it.id == id }?.let { target ->
            ConfirmDialog(
                title = R.string.accounts_identity_delete_title,
                text = stringResource(R.string.accounts_identity_delete_text, target.label, target.address),
                confirm = R.string.accounts_identity_delete_confirm,
                destructive = true,
                onDismiss = { deleting = null },
                onConfirm = {
                    deleting = null
                    scope.launch {
                        note = when (val result = container.accounts.removeIdentity(target.id)) {
                            is RuleResult.Rejected -> resources.getString(result.reason.messageRes())
                            is RuleResult.Ok -> {
                                val moved = result.value.movedDrafts
                                val fallback = identities.firstOrNull { it.id == result.value.fallbackIdentityId }?.label.orEmpty()
                                if (moved > 0) resources.getString(R.string.accounts_identity_deleted_moved, moved, fallback)
                                else resources.getString(R.string.accounts_identity_deleted_none)
                            }
                        }
                    }
                },
            )
        }
    }

    testing?.let { id ->
        identities.firstOrNull { it.id == id }?.let { target ->
            ConfirmDialog(
                title = R.string.accounts_test_title,
                text = stringResource(R.string.accounts_test_text, target.label, target.address),
                confirm = R.string.accounts_test_confirm,
                onDismiss = { testing = null },
                onConfirm = {
                    testing = null
                    onTestSend(target, testSubject, testBody)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityRow(
    identity: MailIdentity,
    accentIndex: Int?,
    isAppDefault: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    testRunning: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onMove: (Int) -> Unit,
    onSetAccountDefault: () -> Unit,
    onSetAppDefault: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccentLabel(identity.label, accentIndex)
            Text(identity.address, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (identity.displayName.isNotBlank()) {
                Text(stringResource(R.string.accounts_identity_name_line, identity.displayName), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.accounts_identity_verification, stringResource(identity.verification.labelRes())),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (identity.verification == SendVerification.UNVERIFIED) FontWeight.Normal else FontWeight.Bold,
                color = if (identity.verification == SendVerification.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (identity.isAccountDefault) Text(stringResource(R.string.accounts_identity_account_default), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (isAppDefault) Text(stringResource(R.string.accounts_identity_app_default), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IdentityAction(identity.label, R.string.accounts_identity_edit, onClick = onEdit)
                IdentityAction(identity.label, R.string.accounts_identity_test, enabled = !testRunning, onClick = onTest)
                if (!identity.isAccountDefault) IdentityAction(identity.label, R.string.accounts_identity_set_account_default, onClick = onSetAccountDefault)
                if (!isAppDefault) IdentityAction(identity.label, R.string.accounts_identity_set_app_default, onClick = onSetAppDefault)
                IdentityAction(identity.label, R.string.accounts_identity_delete, destructive = true, onClick = onDelete)
            }
        }
        MoveButtons(
            upDescription = stringResource(R.string.accounts_identity_move_up, identity.label),
            downDescription = stringResource(R.string.accounts_identity_move_down, identity.label),
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = { onMove(-1) },
            onMoveDown = { onMove(1) },
        )
    }
}

@Composable
private fun IdentityAction(label: String, text: Int, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    val description = stringResource(R.string.accounts_identity_action_desc, label, stringResource(text))
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
        colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
    ) { Text(stringResource(text), fontWeight = if (destructive) FontWeight.Bold else null) }
}

@Composable
private fun TestSendStatus(testSend: TestSend) {
    when (testSend) {
        TestSend.Idle -> Unit
        TestSend.Running -> Waiting(R.string.accounts_test_running)
        TestSend.Error -> Text(stringResource(R.string.accounts_test_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        is TestSend.Result -> {
            Text(stringResource(testResultRes(testSend), testSend.label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.accounts_test_headers), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun testResultRes(result: TestSend.Result): Int = when (result.state) {
    SendState.ACCEPTED, SendState.SAVED -> R.string.accounts_test_accepted
    SendState.SAVE_FAILED -> R.string.accounts_test_accepted_unsaved
    SendState.UNKNOWN -> R.string.accounts_test_unknown
    SendState.DRAFT, SendState.QUEUED, SendState.SENDING, SendState.FAILED -> when (result.failure?.substringBefore('|')) {
        RejectKind.AUTH.name -> R.string.accounts_test_failed_auth
        RejectKind.TLS.name -> R.string.accounts_test_failed_tls
        RejectKind.SENDER.name -> R.string.accounts_test_failed_sender
        RejectKind.RECIPIENT.name -> R.string.accounts_test_failed_recipient
        RejectKind.SERVER.name -> R.string.accounts_test_failed_server
        FailureCodes.NETWORK -> R.string.accounts_test_failed_network
        else -> R.string.accounts_test_failed_other
    }
}

@Composable
private fun IdentityEditor(accountId: Long, editing: MailIdentity?, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(editing?.address.orEmpty()) }
    var displayName by rememberSaveable { mutableStateOf(editing?.displayName.orEmpty()) }
    var signature by rememberSaveable { mutableStateOf(editing?.signature.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(editing?.label.orEmpty()) }
    var rejection by rememberSaveable { mutableStateOf<Rejection?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing == null) R.string.accounts_identity_add_title else R.string.accounts_identity_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(
                    R.string.accounts_identity_address,
                    address,
                    { address = it; rejection = null },
                    keyboardType = KeyboardType.Email,
                    support = stringResource(R.string.accounts_identity_address_changed).takeIf { editing != null },
                )
                FormField(R.string.accounts_identity_name, displayName, { displayName = it })
                FormField(R.string.accounts_identity_label, label, { label = it; rejection = null }, support = stringResource(R.string.accounts_identity_label_support))
                FormField(R.string.accounts_identity_signature, signature, { signature = it }, singleLine = false)
                rejection?.let {
                    Text(
                        stringResource(it.messageRes()),
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val input = IdentityInput(address, displayName, signature, label)
                        val result = if (editing == null) container.accounts.addIdentity(accountId, input) else container.accounts.updateIdentity(editing.id, input)
                        saving = false
                        when (result) {
                            is IdentityResult.Saved -> onDismiss()
                            is IdentityResult.Rejected -> rejection = result.reason
                        }
                    }
                },
            ) { Text(stringResource(R.string.accounts_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_cancel)) } },
    )
}
