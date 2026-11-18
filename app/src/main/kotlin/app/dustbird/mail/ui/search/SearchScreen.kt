package app.dustbird.mail.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.data.repo.ServerSearchResult
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.mailbox.MessageRow
import app.dustbird.mail.ui.mailbox.rememberMessageTimeFormatter
import app.dustbird.mail.ui.theme.CapsuleGap
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.pillFieldColors
import java.time.Instant
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
    val results: List<MessageSummary>? = null,
    val accountLabels: Map<Long, Pair<String, Int>> = emptyMap(),
    val identityLabels: Map<Long, String> = emptyMap(),
    val server: ServerSearch = ServerSearch.Idle,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val server = MutableStateFlow<ServerSearch>(ServerSearch.Idle)
    private val results = combine(query.debounce(250).map { it.trim() }, server) { text, remote ->
        text to (remote as? ServerSearch.Done)?.result?.hits.orEmpty()
    }.flatMapLatest { (text, hits) ->
        if (text.length < MIN_LENGTH) flowOf(null) else container.mail.search(text, null, serverHits = hits).map<List<MessageSummary>, List<MessageSummary>?> { it }
    }

    val state: StateFlow<SearchUiState> = combine(query, results, container.accounts.accounts, container.accounts.identities, server) { text, found, accounts, identities, remote ->
        SearchUiState(
            query = text,
            results = found,
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

    fun searchServer() {
        val text = query.value.trim()
        if (text.length < MIN_LENGTH || server.value == ServerSearch.Running) return
        server.value = ServerSearch.Running
        viewModelScope.launch {
            val ids = container.db.accounts().getAll().map { it.id }
            val result = container.mail.searchServer(text, ids)
            if (query.value.trim() == text) server.value = ServerSearch.Done(result)
        }
    }

    private companion object {
        const val MIN_LENGTH = 1
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

    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(viewModel::setQuery) }
    LaunchedEffect(Unit) { if (field.text.isEmpty()) runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                if (results.isEmpty()) item(key = "empty") { Hint(stringResource(R.string.search_no_results)) }
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
                item(key = "server") { ServerFooter(state.server, viewModel::searchServer) }
            }
        }
    }
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
