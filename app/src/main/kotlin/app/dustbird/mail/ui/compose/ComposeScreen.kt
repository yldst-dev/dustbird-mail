package app.dustbird.mail.ui.compose

import app.dustbird.mail.ui.theme.rememberHaptics
import android.app.Application
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import androidx.compose.ui.res.painterResource
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
import app.dustbird.mail.R
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.mailbox.rememberAnimationsEnabled
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import app.dustbird.mail.ui.theme.FlatMenu
import app.dustbird.mail.ui.theme.MenuEntry
import app.dustbird.mail.ui.theme.FlatSnackbarHost
import app.dustbird.mail.ui.theme.capsuleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComposeRoute(args: ComposeArgs, onClose: () -> Unit, onOpenAgentSettings: () -> Unit = {}) {
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
    LaunchedEffect(vm.exit) {
        when (val exit = vm.exit) {
            ComposeExit.Held -> onClose()
            is ComposeExit.Scheduled -> {
                val time = DateUtils.formatDateTime(context, exit.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                Toast.makeText(context.applicationContext, resources.getString(R.string.compose_scheduled, time), Toast.LENGTH_LONG).show()
                onClose()
            }
            null -> Unit
        }
    }
    var showSheet by remember { mutableStateOf(false) }
    var writingOpen by rememberSaveable { mutableStateOf(false) }
    val agentSettings by remember(container) { container.agent.current }.collectAsStateWithLifecycle(initialValue = null)
    val busy = phase != SendPhase.Idle
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }

    Scaffold(
        snackbarHost = { FlatSnackbarHost(snackbar) },
        topBar = {
            ComposeTopBar(
                title = titleOf(args),
                sending = busy,
                onLeave = leave,
                onSend = vm::sendNow,
                onSchedule = vm::schedule,
                onAttach = { picker.launch(arrayOf("*/*")) },
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
            LoadState.Ready -> ComposeForm(vm, phase, busy, padding, onPickSender = { showSheet = true }, onWrite = { writingOpen = true })
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
    if (writingOpen) {
        val settings = agentSettings
        val accountId = vm.selected?.first?.account?.id
        WriterSheet(
            writing = vm.writing,
            access = when {
                settings == null || !settings.connected -> WriterAccess.OFF
                accountId != null && accountId in settings.blockedAccounts -> WriterAccess.BLOCKED
                else -> WriterAccess.READY
            },
            hasDraft = vm.ownText().isNotBlank(),
            reply = args.mode == ComposeMode.REPLY || args.mode == ComposeMode.REPLY_ALL,
            subjectBlank = vm.subject.text.isBlank(),
            onRun = vm::write,
            onApply = {
                vm.applyWriting()
                writingOpen = false
            },
            onOpenSettings = {
                writingOpen = false
                vm.dismissWriting()
                onOpenAgentSettings()
            },
            onDismiss = {
                writingOpen = false
                vm.dismissWriting()
            },
        )
    }
    ComposeDialogs(vm, phase, onClose, onChangeSender = { showSheet = true })
}

private fun tomorrowAt(hour: Int): Long =
    LocalDate.now().plusDays(1).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val firstDay = remember(today) { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val date = rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= firstDay

            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )
    val time = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var past by remember { mutableStateOf(false) }
    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { pickingTime = true }, enabled = date.selectedDateMillis != null) { Text(stringResource(R.string.compose_schedule_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_schedule_cancel)) } },
        ) { DatePicker(state = date) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.compose_schedule_time_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePicker(state = time)
                    if (past) {
                        Text(
                            stringResource(R.string.compose_schedule_past),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val day = Instant.ofEpochMilli(date.selectedDateMillis ?: firstDay).atZone(ZoneOffset.UTC).toLocalDate()
                        val at = day.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        if (at <= System.currentTimeMillis() + 60_000) past = true else onPick(at)
                    },
                ) { Text(stringResource(R.string.compose_schedule_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.compose_schedule_back)) } },
        )
    }
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
    onSchedule: (Long) -> Unit,
    onAttach: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val scheduleLabel = stringResource(R.string.compose_schedule)
    if (picking) {
        ScheduleDialog(
            onPick = {
                picking = false
                onSchedule(it)
            },
            onDismiss = { picking = false },
        )
    }
    TopAppBar(
        title = { Text(stringResource(title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onLeave) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.compose_close)) }
        },
        actions = {
            IconButton(onClick = onAttach, enabled = !sending) {
                Icon(painterResource(R.drawable.ic_attach_file), contentDescription = stringResource(R.string.compose_attach))
            }
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        onClick = { haptics.confirm(); onSend() },
                        enabled = !sending,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("compose_send"),
                    ) {
                        if (sending) {
                            LoadingIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.compose_sending))
                        } else {
                            Icon(painterResource(R.drawable.ic_send), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.compose_send))
                        }
                    }
                },
                trailingButton = {
                    Box {
                        val turn by animateFloatAsState(if (scheduleOpen) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "schedule arrow")
                        SplitButtonDefaults.TrailingButton(
                            checked = scheduleOpen,
                            onCheckedChange = { scheduleOpen = it },
                            enabled = !sending,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("compose_schedule").semantics { contentDescription = scheduleLabel },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_keyboard_arrow_down),
                                contentDescription = null,
                                modifier = Modifier.size(SplitButtonDefaults.TrailingIconSize).graphicsLayer { rotationZ = turn },
                            )
                        }
                        FlatMenu(
                            expanded = scheduleOpen,
                            onDismissRequest = { scheduleOpen = false },
                            groups = listOf(
                                listOf(
                                    MenuEntry(R.string.compose_schedule_tomorrow_morning, R.drawable.ic_schedule_send) { onSchedule(tomorrowAt(8)) },
                                    MenuEntry(R.string.compose_schedule_tomorrow_afternoon, R.drawable.ic_schedule_send) { onSchedule(tomorrowAt(13)) },
                                ),
                                listOf(MenuEntry(R.string.compose_schedule_pick, R.drawable.ic_calendar_month) { picking = true }),
                            ),
                        )
                    }
                },
            )
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !sending) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.compose_more))
                }
                FlatMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    groups = listOf(
                        listOf(
                            MenuEntry(R.string.compose_menu_save, R.drawable.ic_draft, onSave),
                            MenuEntry(R.string.compose_menu_discard, R.drawable.ic_delete, onDiscard),
                        ),
                    ),
                )
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
private fun ComposeForm(vm: ComposeViewModel, phase: SendPhase, busy: Boolean, padding: PaddingValues, onPickSender: () -> Unit, onWrite: () -> Unit) {
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
            if (vm.to.chips.isEmpty()) toFocus.focus() else bodyFocus.focus()
        }
    }
    LaunchedEffect(focusCc) {
        if (focusCc) {
            ccFocus.focus()
            focusCc = false
        }
    }
    LaunchedEffect(focusBcc) {
        if (focusBcc) {
            bccFocus.focus()
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
                suggestions = vm.suggestions(vm.to),
                onPick = { vm.pick(vm.to, it) },
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
                        suggestions = vm.suggestions(vm.cc),
                        onPick = { vm.pick(vm.cc, it) },
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
                        suggestions = vm.suggestions(vm.bcc),
                        onPick = { vm.pick(vm.bcc, it) },
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
            val attachments by vm.attachments.collectAsStateWithLifecycle()
            if (attachments.isNotEmpty()) {
                FieldDivider()
                AttachmentChips(attachments, enabled, vm::removeAttachment)
            }
            FieldDivider()
            WriterEntry(enabled = enabled, onClick = onWrite, modifier = Modifier.padding(top = 12.dp).testTag("compose_writer"))
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
    val animate = rememberAnimationsEnabled()
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
            shape = capsuleShape(),
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
                shape = capsuleShape(),
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
            shape = capsuleShape(),
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
        shape = capsuleShape(),
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
