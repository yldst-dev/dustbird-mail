package app.dustbird.mail.ui.search

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.data.repo.SearchField
import app.dustbird.mail.data.repo.SearchFilter
import app.dustbird.mail.data.repo.ServerSearchResult
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.mailbox.MessageRow
import app.dustbird.mail.ui.mailbox.folderLabelRes
import app.dustbird.mail.ui.mailbox.rememberMessageTimeFormatter
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.FlatCard
import app.dustbird.mail.ui.theme.FlatMenu
import app.dustbird.mail.ui.theme.MenuEntry
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.pillFieldColors
import app.dustbird.mail.ui.theme.rememberHaptics
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ServerSearch {
    data object Idle : ServerSearch
    data object Running : ServerSearch
    data class Done(val result: ServerSearchResult) : ServerSearch
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter(),
    val period: SearchPeriod = SearchPeriod.ANY,
    val results: List<MessageSummary>? = null,
    val accounts: List<MailAccount> = emptyList(),
    val accountLabels: Map<Long, Pair<String, Int>> = emptyMap(),
    val identityLabels: Map<Long, String> = emptyMap(),
    val server: ServerSearch = ServerSearch.Idle,
)

private data class Criteria(val query: String, val filter: SearchFilter, val period: SearchPeriod)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter())
    private val period = MutableStateFlow(SearchPeriod.ANY)
    private val server = MutableStateFlow<ServerSearch>(ServerSearch.Idle)
    private val criteria = combine(query, filter, period, ::Criteria)
    private val results = combine(query.debounce(250).map { it.trim() }, filter, server) { text, chosen, remote ->
        Triple(text, chosen, (remote as? ServerSearch.Done)?.result?.hits.orEmpty())
    }.flatMapLatest { (text, chosen, hits) ->
        if (text.isEmpty() && !chosen.active) flowOf(null) else container.mail.search(text, chosen, serverHits = hits).map<List<MessageSummary>, List<MessageSummary>?> { it }
    }

    val state: StateFlow<SearchUiState> = combine(criteria, results, container.accounts.accounts, container.accounts.identities, server) { current, found, accounts, identities, remote ->
        SearchUiState(
            query = current.query,
            filter = current.filter,
            period = current.period,
            results = found,
            accounts = accounts,
            accountLabels = if (accounts.size > 1) accounts.withIndex().associate { it.value.id to (it.value.label to it.index) } else emptyMap(),
            identityLabels = identities.associate { it.id to it.label },
            server = remote,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) {
        if (text == query.value) return
        query.value = text
        server.value = ServerSearch.Idle
    }

    fun setPeriod(choice: SearchPeriod) {
        val (since, until) = SearchPeriods.range(choice, ZonedDateTime.now())
        period.value = choice
        filter.value = filter.value.copy(since = since, until = until)
    }

    fun setCustomPeriod(since: Long, until: Long) {
        period.value = SearchPeriod.CUSTOM
        filter.value = filter.value.copy(since = since, until = until)
    }

    fun update(transform: (SearchFilter) -> SearchFilter) {
        filter.value = transform(filter.value)
    }

    fun clearFilters() {
        period.value = SearchPeriod.ANY
        filter.value = SearchFilter()
    }

    fun searchServer() {
        val text = query.value.trim()
        if (text.isEmpty() || server.value == ServerSearch.Running) return
        server.value = ServerSearch.Running
        viewModelScope.launch {
            val ids = container.db.accounts().getAll().map { it.id }
            val result = container.mail.searchServer(text, ids)
            if (query.value.trim() == text) server.value = ServerSearch.Done(result)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchRoute(onBack: () -> Unit, onOpenMessage: (Long) -> Unit) {
    val viewModel = appViewModel { SearchViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val formatter = rememberMessageTimeFormatter()
    val now = remember(state.results) { Instant.now() }
    var choosingPeriod by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(viewModel::setQuery) }
    LaunchedEffect(Unit) { if (field.text.isEmpty() && !state.filter.active) runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.search_back)) }
                    TextField(
                        state = field,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        trailingIcon = {
                            if (field.text.isNotEmpty()) {
                                IconButton(onClick = { field.clearText() }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        shape = Pill,
                        colors = pillFieldColors(),
                    )
                }
                FilterBar(
                    state = state,
                    onPeriod = { if (it == SearchPeriod.CUSTOM) choosingPeriod = true else viewModel.setPeriod(it) },
                    onUpdate = viewModel::update,
                    onClear = viewModel::clearFilters,
                )
            }
        },
    ) { padding ->
        val results = state.results
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(CapsuleGap),
        ) {
            if (results == null) {
                item(key = "prompt") { Hint(stringResource(R.string.search_prompt)) }
            } else {
                if (results.isEmpty()) {
                    item(key = "empty") { Hint(stringResource(if (state.query.isBlank()) R.string.search_no_results_filtered else R.string.search_no_results)) }
                } else {
                    item(key = "count") {
                        Text(
                            text = stringResource(R.string.search_result_count, results.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                items(results, key = { it.id }) { message ->
                    val account = state.accountLabels[message.accountId]
                    MessageRow(
                        message = message,
                        time = formatter.format(message.sortDate, now),
                        accountLabel = account?.first,
                        accountIndex = account?.second ?: 0,
                        identityLabels = state.identityLabels,
                        selected = null,
                        onClick = { onOpenMessage(message.id) },
                    )
                }
                if (state.query.isNotBlank()) item(key = "server") { ServerFooter(state.server, viewModel::searchServer) }
            }
        }
    }

    if (choosingPeriod) {
        PeriodSheet(
            since = state.filter.since.takeIf { state.period == SearchPeriod.CUSTOM },
            until = state.filter.until.takeIf { state.period == SearchPeriod.CUSTOM },
            onApply = { since, until ->
                choosingPeriod = false
                viewModel.setCustomPeriod(since, until)
            },
            onDismiss = { choosingPeriod = false },
        )
    }
}

@Composable
private fun FilterBar(state: SearchUiState, onPeriod: (SearchPeriod) -> Unit, onUpdate: ((SearchFilter) -> SearchFilter) -> Unit, onClear: () -> Unit) {
    val filter = state.filter
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val periodLabel = when (state.period) {
        SearchPeriod.ANY -> stringResource(R.string.search_filter_period)
        SearchPeriod.CUSTOM -> rangeText(context, filter.since, filter.until)
        else -> stringResource(periodText(state.period))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuChip(periodLabel, R.drawable.ic_calendar_month, state.period != SearchPeriod.ANY) { close ->
            val pick = { choice: SearchPeriod ->
                haptics.tick()
                onPeriod(choice)
                close()
            }
            listOf(
                SearchPeriods.presets.map { MenuEntry(stringResource(periodText(it)), R.drawable.ic_calendar_month, state.period == it) { pick(it) } },
                listOf(MenuEntry(stringResource(R.string.search_period_custom), R.drawable.ic_edit, state.period == SearchPeriod.CUSTOM) { pick(SearchPeriod.CUSTOM) }),
                listOf(MenuEntry(stringResource(R.string.search_period_any), R.drawable.ic_history, state.period == SearchPeriod.ANY) { pick(SearchPeriod.ANY) }),
            )
        }
        val role = filter.role
        MenuChip(role?.let { stringResource(folderLabelRes(it)) } ?: stringResource(R.string.search_filter_folder), R.drawable.ic_folder, role != null) { close ->
            val pick = { choice: FolderRole? ->
                haptics.tick()
                onUpdate { it.copy(role = choice) }
                close()
            }
            listOf(
                searchRoles.map { (option, icon) -> MenuEntry(stringResource(folderLabelRes(option)), icon, role == option) { pick(option) } },
                listOf(MenuEntry(stringResource(R.string.search_folder_any), R.drawable.ic_stacks, role == null) { pick(null) }),
            )
        }
        if (state.accounts.size > 1) {
            val chosen = state.accounts.firstOrNull { it.id == filter.accountId }
            MenuChip(chosen?.label ?: stringResource(R.string.search_filter_account), R.drawable.ic_person, chosen != null) { close ->
                val pick = { id: Long? ->
                    haptics.tick()
                    onUpdate { it.copy(accountId = id) }
                    close()
                }
                listOf(
                    state.accounts.map { account -> MenuEntry(account.displayName.ifBlank { account.label }, R.drawable.ic_inbox, account.id == filter.accountId) { pick(account.id) } },
                    listOf(MenuEntry(stringResource(R.string.search_account_any), R.drawable.ic_stacks, chosen == null) { pick(null) }),
                )
            }
        }
        MenuChip(
            if (filter.field == SearchField.ALL) stringResource(R.string.search_filter_field) else stringResource(fieldText(filter.field)),
            R.drawable.ic_tune,
            filter.field != SearchField.ALL,
        ) { close ->
            listOf(
                SearchField.entries.map { option ->
                    MenuEntry(stringResource(fieldText(option)), fieldIcon(option), filter.field == option) {
                        haptics.tick()
                        onUpdate { it.copy(field = option) }
                        close()
                    }
                },
            )
        }
        ToggleChip(R.string.search_filter_unread, R.drawable.ic_mark_email_unread, filter.unread) { value ->
            haptics.toggle(value)
            onUpdate { it.copy(unread = value) }
        }
        ToggleChip(R.string.search_filter_starred, R.drawable.ic_star, filter.starred) { value ->
            haptics.toggle(value)
            onUpdate { it.copy(starred = value) }
        }
        ToggleChip(R.string.search_filter_attachment, R.drawable.ic_attach_file, filter.attachment) { value ->
            haptics.toggle(value)
            onUpdate { it.copy(attachment = value) }
        }
        if (filter.active) {
            TextButton(onClick = {
                haptics.tick()
                onClear()
            }) { Text(stringResource(R.string.search_filter_clear)) }
        }
    }
}

private val searchRoles = listOf(
    FolderRole.INBOX to R.drawable.ic_inbox,
    FolderRole.SENT to R.drawable.ic_send,
    FolderRole.ARCHIVE to R.drawable.ic_archive,
    FolderRole.SPAM to R.drawable.ic_report,
    FolderRole.TRASH to R.drawable.ic_delete,
)

@StringRes
private fun periodText(period: SearchPeriod): Int = when (period) {
    SearchPeriod.TODAY -> R.string.search_period_today
    SearchPeriod.WEEK -> R.string.search_period_week
    SearchPeriod.MONTH -> R.string.search_period_month
    SearchPeriod.YEAR -> R.string.search_period_year
    SearchPeriod.CUSTOM -> R.string.search_period_custom
    SearchPeriod.ANY -> R.string.search_period_any
}

@StringRes
private fun fieldText(field: SearchField): Int = when (field) {
    SearchField.ALL -> R.string.search_field_all
    SearchField.SENDER -> R.string.search_field_sender
    SearchField.RECIPIENT -> R.string.search_field_recipient
    SearchField.SUBJECT -> R.string.search_field_subject
}

@DrawableRes
private fun fieldIcon(field: SearchField): Int = when (field) {
    SearchField.ALL -> R.drawable.ic_search
    SearchField.SENDER -> R.drawable.ic_person
    SearchField.RECIPIENT -> R.drawable.ic_send
    SearchField.SUBJECT -> R.drawable.ic_edit
}

private fun rangeText(context: android.content.Context, since: Long?, until: Long?): String {
    val start = since ?: return ""
    val end = (until ?: System.currentTimeMillis()) - 60_000
    val zone = ZoneId.systemDefault()
    val whole = Instant.ofEpochMilli(start).atZone(zone).toLocalTime() == LocalTime.MIDNIGHT &&
        Instant.ofEpochMilli(end + 60_000).atZone(zone).toLocalTime() == LocalTime.MIDNIGHT
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or (if (whole) 0 else DateUtils.FORMAT_SHOW_TIME)
    return DateUtils.formatDateRange(context, start, end.coerceAtLeast(start), flags)
}

@Composable
private fun MenuChip(label: String, @DrawableRes icon: Int, selected: Boolean, entries: @Composable (close: () -> Unit) -> List<List<MenuEntry>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            shape = Pill,
        )
        FlatMenu(expanded = open, onDismissRequest = { open = false }, groups = entries { open = false })
    }
}

@Composable
private fun ToggleChip(@StringRes label: Int, @DrawableRes icon: Int, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(stringResource(label)) },
        leadingIcon = {
            Icon(
                painterResource(if (selected) R.drawable.ic_check else icon),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        shape = Pill,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeriodSheet(since: Long?, until: Long?, onApply: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now() }
    var start by remember { mutableStateOf(since?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: today.minusDays(6).atStartOfDay()) }
    var end by remember {
        mutableStateOf(until?.let { Instant.ofEpochMilli(it - 60_000).atZone(zone).toLocalDateTime() } ?: today.atTime(23, 59))
    }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val valid = start.isBefore(end)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.search_period_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.search_period_sheet_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(Pick(true, false) to R.string.search_period_start, Pick(false, false) to R.string.search_period_end).forEach { (pick, label) ->
                val value = if (pick.start) start else end
                FlatCard(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { picking = pick }, modifier = Modifier.weight(1.4f)) {
                                Text(DateUtils.formatDateTime(context, millis(value, zone), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL), maxLines = 1)
                            }
                            FilledTonalButton(onClick = { picking = pick.copy(time = true) }, modifier = Modifier.weight(1f)) {
                                Text(DateUtils.formatDateTime(context, millis(value, zone), DateUtils.FORMAT_SHOW_TIME), maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (!valid) {
                Text(
                    stringResource(R.string.search_period_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = { onApply(millis(start, zone), SearchPeriods.inclusiveEnd(millis(end, zone))) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.search_period_apply)) }
        }
    }
    picking?.let { pick ->
        val value = if (pick.start) start else end
        val done = { next: LocalDateTime ->
            if (pick.start) start = next else end = next
            picking = null
        }
        if (pick.time) {
            TimeDialog(value.toLocalTime(), onPick = { done(value.toLocalDate().atTime(it)) }, onDismiss = { picking = null })
        } else {
            DateDialog(value.toLocalDate(), onPick = { done(it.atTime(value.toLocalTime())) }, onDismiss = { picking = null })
        }
    }
}

private data class Pick(val start: Boolean, val time: Boolean)

private fun millis(value: LocalDateTime, zone: ZoneId): Long = value.atZone(zone).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.search_period_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.search_period_cancel)) } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_period_time_title)) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.search_period_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.search_period_cancel)) } },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerFooter(server: ServerSearch, onSearch: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        when (server) {
            ServerSearch.Idle -> FilledTonalButton(onClick = onSearch) { Text(stringResource(R.string.search_server_action)) }
            ServerSearch.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator()
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.search_server_running), style = MaterialTheme.typography.bodyMedium)
            }
            is ServerSearch.Done -> Text(
                text = when {
                    server.result.failedAccounts > 0 -> stringResource(R.string.search_server_failed)
                    server.result.hits.isNotEmpty() -> stringResource(R.string.search_server_added, server.result.hits.size)
                    else -> stringResource(R.string.search_server_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
