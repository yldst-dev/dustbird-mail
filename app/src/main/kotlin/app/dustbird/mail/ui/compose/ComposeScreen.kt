package app.dustbird.mail.ui.compose

import android.app.Application
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import app.dustbird.mail.R
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposeRoute(args: ComposeArgs, onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val vm: ComposeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ComposeViewModel(container, context.applicationContext as Application, args, createSavedStateHandle()) }
        },
    )
    val snackbar = remember { SnackbarHostState() }
    val phase = vm.phase
    val leave = {
        if (vm.leave()) Toast.makeText(context.applicationContext, R.string.compose_kept_as_draft, Toast.LENGTH_SHORT).show()
        onClose()
    }
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    LaunchedEffect(vm) {
        for (message in vm.events) snackbar.showSnackbar(resources.getString(message.res, *message.args.toTypedArray()))
    }
    LaunchedEffect(phase) {
        if (phase == SendPhase.Done) {
            Toast.makeText(context.applicationContext, R.string.compose_sent, Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    var showSheet by remember { mutableStateOf(false) }
    val busy = phase != SendPhase.Idle

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ComposeTopBar(
                title = titleOf(args),
                sending = busy,
                onLeave = leave,
                onSend = vm::send,
                onSave = vm::saveDraft,
                onDiscard = {
                    if (vm.hasWork()) {
                        vm.dialog = ComposeDialog.Discard
                    } else {
                        vm.discard()
                        onClose()
                    }
                },
            )
        },
    ) { padding ->
        when (vm.loadState) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            LoadState.NoAccount -> NoAccount(onClose, Modifier.padding(padding))
            LoadState.Ready -> ComposeForm(vm, phase, busy, padding, onPickSender = { showSheet = true })
        }
    }

    if (showSheet) {
        SenderSheet(
            groups = vm.groups,
            selectedId = vm.identityId,
            onSelect = {
                vm.selectIdentity(it)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
    ComposeDialogs(vm, phase, onClose, onChangeSender = { showSheet = true })
}

@StringRes
private fun titleOf(args: ComposeArgs): Int = when {
    args.draftId != null -> R.string.compose_title_draft
    args.mode == ComposeMode.REPLY -> R.string.compose_title_reply
    args.mode == ComposeMode.REPLY_ALL -> R.string.compose_title_reply_all
    args.mode == ComposeMode.FORWARD -> R.string.compose_title_forward
    else -> R.string.compose_title_new
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ComposeTopBar(
    @StringRes title: Int,
    sending: Boolean,
    onLeave: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onLeave) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_close)) }
        },
        actions = {
            Button(
                onClick = onSend,
                enabled = !sending,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("compose_send"),
            ) {
                if (sending) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.compose_sending))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.compose_send))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !sending) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.compose_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_menu_save)) },
                        onClick = {
                            menuOpen = false
                            onSave()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_menu_discard)) },
                        onClick = {
                            menuOpen = false
                            onDiscard()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NoAccount(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.compose_no_account_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.compose_no_account_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.compose_action_close)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ComposeForm(vm: ComposeViewModel, phase: SendPhase, busy: Boolean, padding: PaddingValues, onPickSender: () -> Unit) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val enabled = !busy
    val toFocus = remember { FocusRequester() }
    val ccFocus = remember { FocusRequester() }
    val bccFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    var focusCc by remember { mutableStateOf(false) }
    var focusBcc by remember { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focused) {
            focused = true
            if (vm.to.chips.isEmpty()) toFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }
    LaunchedEffect(focusCc) {
        if (focusCc) {
            ccFocus.requestFocus()
            focusCc = false
        }
    }
    LaunchedEffect(focusBcc) {
        if (focusBcc) {
            bccFocus.requestFocus()
            focusBcc = false
        }
    }
    val statusRes = when {
        phase is SendPhase.Progress -> if (phase.storingCopy) R.string.compose_status_storing_copy else R.string.compose_status_sending
        vm.saving -> R.string.compose_status_saving
        else -> null
    }
    val problem = vm.recipientProblem
    val selected = vm.selected
    val (enter, exit) = revealTransitions()

    Box(
        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp)
                .padding(bottom = 24.dp),
        ) {
            if (statusRes != null) StatusRow(stringResource(statusRes))
            if (selected != null) {
                val (group, identity) = selected
                SenderRow(
                    identity = identity,
                    account = group.account,
                    accountIndex = vm.accountIndex(group.account.id),
                    showAccount = vm.multipleAccounts,
                    enabled = enabled,
                    onClick = onPickSender,
                    modifier = Modifier.testTag("compose_sender"),
                )
                if (vm.multipleAccounts) HintRow(stringResource(R.string.compose_sender_account_note, group.account.displayName))
                if (!vm.senderConfirmed) {
                    HintRow(stringResource(R.string.compose_sender_unsure), warning = true) {
                        TextActionButton(R.string.compose_sender_confirm, vm::confirmSender)
                    }
                }
                if (identity.verification == SendVerification.FAILED) {
                    HintRow(stringResource(R.string.compose_sender_failed_warning), warning = true)
                }
            }
            FieldDivider()
            RecipientRow(
                label = stringResource(R.string.compose_label_to),
                field = vm.to,
                enabled = enabled,
                tag = "compose_to",
                emptyError = problem == RecipientProblem.EMPTY,
                focusRequester = toFocus,
            )
            if (!vm.showCc || !vm.showBcc) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!vm.showCc) {
                        TextActionButton(R.string.compose_add_cc, {
                            vm.showCc = true
                            focusCc = true
                        }, Modifier.testTag("compose_add_cc"))
                    }
                    if (!vm.showBcc) {
                        TextActionButton(R.string.compose_add_bcc, {
                            vm.showBcc = true
                            focusBcc = true
                        }, Modifier.testTag("compose_add_bcc"))
                    }
                }
            }
            AnimatedVisibility(vm.showCc, enter = enter, exit = exit) {
                Column {
                    FieldDivider()
                    RecipientRow(
                        label = stringResource(R.string.compose_label_cc),
                        field = vm.cc,
                        enabled = enabled,
                        tag = "compose_cc",
                        focusRequester = ccFocus,
                    )
                }
            }
            AnimatedVisibility(vm.showBcc, enter = enter, exit = exit) {
                Column {
                    FieldDivider()
                    RecipientRow(
                        label = stringResource(R.string.compose_label_bcc),
                        field = vm.bcc,
                        enabled = enabled,
                        tag = "compose_bcc",
                        note = stringResource(R.string.compose_bcc_note),
                        hidden = true,
                        focusRequester = bccFocus,
                    )
                }
            }
            FieldDivider()
            LabeledField(stringResource(R.string.compose_label_subject), vm.subject, enabled, "compose_subject")
            FieldDivider()
            LabeledField(
                label = stringResource(R.string.compose_label_body),
                state = vm.body,
                enabled = enabled,
                tag = "compose_body",
                singleLine = false,
                focusRequester = bodyFocus,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusRow(text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun revealTransitions(): Pair<EnterTransition, ExitTransition> {
    val resolver = LocalContext.current.contentResolver
    val animate = remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return if (animate) {
        (fadeIn(effects) + expandVertically(spatial)) to (fadeOut(effects) + shrinkVertically(spatial))
    } else {
        EnterTransition.None to ExitTransition.None
    }
}

@Composable
private fun ComposeDialogs(vm: ComposeViewModel, phase: SendPhase, onClose: () -> Unit, onChangeSender: () -> Unit) {
    when (vm.dialog) {
        ComposeDialog.EmptySubject -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(stringResource(R.string.compose_dialog_subject_title)) },
            text = { Text(stringResource(R.string.compose_dialog_subject_text)) },
            confirmButton = {
                TextActionButton(R.string.compose_dialog_subject_confirm, {
                    vm.dialog = null
                    vm.send()
                })
            },
            dismissButton = { TextActionButton(R.string.compose_dialog_subject_cancel, { vm.dialog = null }) },
        )
        ComposeDialog.SenderCheck -> {
            val identity = vm.selected?.second
            val unsure = !vm.senderConfirmed
            val failed = identity?.verification == SendVerification.FAILED
            AlertDialog(
                onDismissRequest = { vm.dialog = null },
                title = { Text(stringResource(R.string.compose_dialog_sender_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (unsure) Text(stringResource(R.string.compose_dialog_sender_unsure_text, identity?.address.orEmpty()))
                        if (failed) Text(stringResource(R.string.compose_dialog_sender_failed_text, identity.address))
                    }
                },
                confirmButton = { TextActionButton(R.string.compose_dialog_sender_confirm, vm::confirmSenderAndSend) },
                dismissButton = {
                    TextActionButton(R.string.compose_dialog_sender_change, {
                        vm.dialog = null
                        onChangeSender()
                    })
                },
            )
        }
        ComposeDialog.Discard -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(stringResource(R.string.compose_dialog_discard_title)) },
            text = { Text(stringResource(R.string.compose_dialog_discard_text)) },
            confirmButton = {
                TextActionButton(R.string.compose_dialog_discard_confirm, {
                    vm.dialog = null
                    vm.discard()
                    onClose()
                })
            },
            dismissButton = { TextActionButton(R.string.compose_dialog_discard_cancel, { vm.dialog = null }) },
        )
        null -> Unit
    }
    when (phase) {
        is SendPhase.Failed -> OutcomeDialog(
            title = R.string.compose_failed_title,
            text = stringResource(failureText(phase.kind)),
            confirm = R.string.compose_action_retry,
            onConfirm = vm::retry,
            dismiss = R.string.compose_action_keep_in_outbox,
            onDismiss = onClose,
        )
        SendPhase.Queued -> OutcomeDialog(
            title = R.string.compose_queued_title,
            text = stringResource(R.string.compose_queued_text),
            confirm = R.string.compose_action_retry,
            onConfirm = vm::retry,
            dismiss = R.string.compose_action_keep_in_outbox,
            onDismiss = onClose,
        )
        SendPhase.Unknown -> OutcomeDialog(
            title = R.string.compose_unknown_title,
            text = stringResource(R.string.compose_unknown_text),
            confirm = R.string.compose_action_close,
            onConfirm = onClose,
        )
        is SendPhase.CopyFailed -> OutcomeDialog(
            title = R.string.compose_copy_failed_title,
            text = stringResource(R.string.compose_copy_failed_text, stringResource(copyFailureText(phase.reason))),
            confirm = R.string.compose_action_ok,
            onConfirm = onClose,
        )
        else -> Unit
    }
}

@Composable
private fun OutcomeDialog(
    @StringRes title: Int,
    text: String,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    @StringRes dismiss: Int? = null,
    onDismiss: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(title)) },
        text = { Text(text) },
        confirmButton = { TextActionButton(confirm, onConfirm) },
        dismissButton = dismiss?.let { { TextActionButton(it, onDismiss) } },
    )
}

@StringRes
private fun failureText(kind: FailureKind): Int = when (kind) {
    FailureKind.AUTH -> R.string.compose_failed_auth
    FailureKind.TLS -> R.string.compose_failed_tls
    FailureKind.SENDER -> R.string.compose_failed_sender
    FailureKind.RECIPIENT -> R.string.compose_failed_recipient
    FailureKind.SERVER -> R.string.compose_failed_server
    FailureKind.NETWORK -> R.string.compose_failed_network
    FailureKind.OTHER -> R.string.compose_failed_other
}

@StringRes
private fun copyFailureText(reason: CopyFailure): Int = when (reason) {
    CopyFailure.NO_FOLDER -> R.string.compose_copy_failed_no_folder
    CopyFailure.NOT_BY_SERVER -> R.string.compose_copy_failed_not_by_server
    CopyFailure.ERROR -> R.string.compose_copy_failed_error
}
