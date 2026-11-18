package app.dustbird.mail.ui.agent

import app.dustbird.mail.ui.theme.CapsuleGap
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.agent.AgentRunError
import app.dustbird.mail.agent.AgentService
import app.dustbird.mail.agent.PendingApproval
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.db.AgentItemEntity
import app.dustbird.mail.data.db.AgentSessionEntity
import app.dustbird.mail.ui.appViewModel
import app.dustbird.mail.ui.theme.CapsulePadding
import app.dustbird.mail.ui.theme.Pill
import app.dustbird.mail.ui.theme.capsuleItemShapes
import app.dustbird.mail.ui.theme.cardItemShapes
import app.dustbird.mail.ui.theme.capsuleShape
import app.dustbird.mail.ui.theme.pillFieldColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class ToolStatus { RUNNING, DONE, DENIED, FAILED }

sealed interface ChatEntry {
    val key: Long

    data class User(override val key: Long, val text: String) : ChatEntry
    data class Assistant(override val key: Long, val text: String) : ChatEntry
    data class Tool(override val key: Long, val name: String, val status: ToolStatus, val draftId: Long?) : ChatEntry
}

data class AgentUiState(
    val configured: Boolean = true,
    val sessionId: Long? = null,
    val entries: List<ChatEntry> = emptyList(),
    val partial: String = "",
    val running: Boolean = false,
    val error: AgentRunError? = null,
    val approval: PendingApproval? = null,
    val sessions: List<AgentSessionEntity> = emptyList(),
)

fun chatEntries(items: List<AgentItemEntity>): List<ChatEntry> {
    val outputs = items.filter { it.kind == AgentService.KIND_OUTPUT }.associateBy { it.callId }
    return items.mapNotNull { item ->
        when {
            item.kind == AgentService.KIND_USER -> ChatEntry.User(item.id, item.text.orEmpty())
            item.kind != AgentService.KIND_MODEL -> null
            item.callId != null -> {
                val output = outputs[item.callId]?.output
                val status = when {
                    output == null -> ToolStatus.RUNNING
                    "did not approve" in output -> ToolStatus.DENIED
                    output.startsWith("{\"error\"") -> ToolStatus.FAILED
                    else -> ToolStatus.DONE
                }
                val draftId = if (item.toolName == "create_draft" && status == ToolStatus.DONE) {
                    runCatching { JSONObject(output.orEmpty()).optLong("draft_id", -1).takeIf { it >= 0 } }.getOrNull()
                } else {
                    null
                }
                ChatEntry.Tool(item.id, item.toolName.orEmpty(), status, draftId)
            }
            !item.text.isNullOrBlank() -> ChatEntry.Assistant(item.id, item.text)
            else -> null
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(private val container: AppContainer, initialSession: Long?) : ViewModel() {
    private val agent = container.agent
    private val sessionId = MutableStateFlow(initialSession)
    private val items = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else agent.items(id) }
    private val live = combine(agent.running, agent.partial, agent.errors, agent.approval) { running, partial, errors, approval ->
        Live(running, partial, errors, approval)
    }

    private class Live(val running: Set<Long>, val partial: Map<Long, String>, val errors: Map<Long, AgentRunError>, val approval: PendingApproval?)

    val state: StateFlow<AgentUiState> = combine(sessionId, items, live, agent.current, agent.sessions) { id, list, now, settings, sessions ->
        AgentUiState(
            configured = settings.mode != AgentMode.OFF && settings.consent,
            sessionId = id,
            entries = chatEntries(list),
            partial = id?.let { now.partial[it] }.orEmpty(),
            running = id != null && id in now.running,
            error = id?.let { now.errors[it] },
            approval = now.approval?.takeIf { it.sessionId == id },
            sessions = sessions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentUiState())

    fun send(text: String, contextNote: String?) {
        viewModelScope.launch {
            val existing = sessionId.value
            val id = existing ?: agent.createSession().also { sessionId.value = it }
            agent.send(id, if (existing == null && contextNote != null) "$text\n($contextNote)" else text)
        }
    }

    fun stop() {
        sessionId.value?.let(agent::stop)
    }

    fun answer(approved: Boolean) {
        state.value.approval?.let { agent.answer(it.id, approved) }
    }

    fun newChat() {
        sessionId.value = null
    }

    fun open(id: Long) {
        sessionId.value = id
    }

    fun delete(id: Long) {
        if (sessionId.value == id) sessionId.value = null
        viewModelScope.launch { agent.deleteSession(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun AgentRoute(messageId: Long?, sessionId: Long?, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenDraft: (Long) -> Unit) {
    val viewModel = appViewModel(key = "agent-${sessionId ?: -1}-${messageId ?: -1}") { AgentViewModel(it, sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input = remember { TextFieldState() }
    val listState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }
    val contextNote = messageId?.let { stringResource(R.string.agent_context_note, it) }

    LaunchedEffect(state.entries.size, state.partial.length, state.approval?.id, state.running) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    fun submit(text: String) {
        if (text.isBlank() || state.running) return
        viewModel.send(text, contextNote)
        input.clearText()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.agent_back)) }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) { Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.agent_history)) }
                    IconButton(onClick = viewModel::newChat) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.agent_new_chat)) }
                    IconButton(onClick = onOpenSettings) { Icon(painterResource(R.drawable.ic_tune), contentDescription = stringResource(R.string.agent_settings)) }
                },
            )
        },
        bottomBar = {
            if (state.configured) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        state = input,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = pillFieldColors(),
                    )
                    if (state.running) {
                        FilledIconButton(onClick = viewModel::stop, modifier = Modifier.size(56.dp)) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.agent_stop))
                        }
                    } else {
                        FilledIconButton(onClick = { submit(input.text.toString()) }, enabled = input.text.isNotBlank(), modifier = Modifier.size(56.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.agent_send))
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            !state.configured -> Setup(onOpenSettings, Modifier.padding(padding))
            state.entries.isEmpty() && !state.running && state.error == null -> Welcome(
                hasContext = messageId != null,
                onPick = ::submit,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { it.key }) { entry ->
                    when (entry) {
                        is ChatEntry.User -> UserBubble(entry.text)
                        is ChatEntry.Assistant -> AssistantText(entry.text)
                        is ChatEntry.Tool -> ToolLine(entry, running = state.running, onOpenDraft = onOpenDraft)
                    }
                }
                if (state.partial.isNotBlank()) item(key = "partial") { AssistantText(state.partial) }
                state.approval?.let { approval -> item(key = "approval") { ApprovalCard(approval, viewModel::answer) } }
                if (state.running && state.partial.isBlank() && state.approval == null) item(key = "thinking") { Thinking() }
                state.error?.let { error -> item(key = "error") { ErrorCard(error) } }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            shape = MaterialTheme.shapes.extraExtraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item(key = "title") {
                    Text(stringResource(R.string.agent_history), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                }
                if (state.sessions.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.agent_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
                items(state.sessions, key = { it.id }) { session ->
                    ListItem(
                        onClick = {
                            viewModel.open(session.id)
                            showHistory = false
                        },
                        shapes = capsuleItemShapes(),
                        contentPadding = CapsulePadding,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = {
                            IconButton(onClick = { viewModel.delete(session.id) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.agent_delete_chat))
                            }
                        },
                    ) { Text(session.title.ifBlank { stringResource(R.string.agent_history_untitled) }, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun Setup(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(96.dp).clip(Pill))
        Text(stringResource(R.string.agent_setup_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.agent_setup_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.agent_setup_action)) }
    }
}

private class ManagerTask(@StringRes val title: Int, @StringRes val request: Int, @DrawableRes val icon: Int)

private val managerTasks = listOf(
    ManagerTask(R.string.agent_task_summary, R.string.agent_suggest_unread, R.drawable.ic_mark_email_unread),
    ManagerTask(R.string.agent_task_find, R.string.agent_suggest_reply, R.drawable.ic_search),
    ManagerTask(R.string.agent_task_draft, R.string.agent_suggest_draft, R.drawable.ic_reply),
    ManagerTask(R.string.agent_task_tidy, R.string.agent_suggest_tidy, R.drawable.ic_archive),
)

private val messageTasks = listOf(
    ManagerTask(R.string.agent_task_summary_one, R.string.agent_context_summarize, R.drawable.ic_mark_email_unread),
    ManagerTask(R.string.agent_task_draft_one, R.string.agent_context_reply, R.drawable.ic_reply),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Welcome(hasContext: Boolean, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(72.dp).clip(Pill))
        Text(stringResource(R.string.agent_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(if (hasContext) R.string.agent_context_label else R.string.agent_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(Modifier.widthIn(max = 560.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(CapsuleGap)) {
            (if (hasContext) messageTasks else managerTasks).forEach { task ->
                val request = stringResource(task.request)
                ListItem(
                    onClick = { onPick(request) },
                    shapes = cardItemShapes(),
                    contentPadding = CapsulePadding,
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = { Icon(painterResource(task.icon), contentDescription = null) },
                    supportingContent = { Text(request) },
                ) { Text(stringResource(task.title), style = MaterialTheme.typography.titleMediumEmphasized) }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = capsuleShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(end = 24.dp)) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolLine(entry: ChatEntry.Tool, running: Boolean, onOpenDraft: (Long) -> Unit) {
    val name = stringResource(toolLabel(entry.name))
    val status = if (entry.status == ToolStatus.RUNNING && !running) ToolStatus.FAILED else entry.status
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = Pill, color = if (status == ToolStatus.FAILED) scheme.errorContainer else scheme.surfaceContainerHigh) {
            Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (status) {
                    ToolStatus.RUNNING -> LoadingIndicator(Modifier.size(20.dp))
                    ToolStatus.DONE -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
                    ToolStatus.DENIED -> Icon(painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(16.dp))
                    ToolStatus.FAILED -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    stringResource(
                        when (status) {
                            ToolStatus.RUNNING -> R.string.agent_tool_running
                            ToolStatus.DONE -> R.string.agent_tool_done
                            ToolStatus.DENIED -> R.string.agent_tool_denied
                            ToolStatus.FAILED -> R.string.agent_tool_failed
                        },
                        name,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        entry.draftId?.let { draftId -> OutlinedButton(onClick = { onOpenDraft(draftId) }) { Text(stringResource(R.string.agent_open_draft)) } }
    }
}

@Composable
private fun ApprovalCard(approval: PendingApproval, onAnswer: (Boolean) -> Unit) {
    Surface(shape = capsuleShape(), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agent_approval_title), style = MaterialTheme.typography.labelLarge)
            Text(approval.preview.title, style = MaterialTheme.typography.titleMediumEmphasized)
            approval.preview.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.agent_approve)) }
                OutlinedButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.agent_deny)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.agent_thinking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(error: AgentRunError) {
    Surface(shape = capsuleShape(), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(agentFailureText(error.failure)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
fun AgentFailureMessage(failure: BackendFailure, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(agentFailureText(failure)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (detail.isNotBlank()) {
            SelectionContainer {
                Text(
                    stringResource(R.string.agent_error_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun agentFailureText(failure: BackendFailure): Int = when (failure) {
    BackendFailure.NOT_CONFIGURED -> R.string.agent_error_not_configured
    BackendFailure.AUTH -> R.string.agent_error_auth
    BackendFailure.USAGE_LIMIT -> R.string.agent_error_usage
    BackendFailure.NETWORK -> R.string.agent_error_network
    BackendFailure.REJECTED -> R.string.agent_error_rejected
    BackendFailure.SERVER -> R.string.agent_error_server
    BackendFailure.INCOMPLETE -> R.string.agent_error_incomplete
}

fun toolLabel(name: String): Int = when (name) {
    "list_accounts" -> R.string.agent_tool_list_accounts
    "list_folders" -> R.string.agent_tool_list_folders
    "list_mail" -> R.string.agent_tool_list_mail
    "search_mail" -> R.string.agent_tool_search_mail
    "read_mail" -> R.string.agent_tool_read_mail
    "create_draft" -> R.string.agent_tool_create_draft
    "mark_mail" -> R.string.agent_tool_mark_mail
    "move_mail" -> R.string.agent_tool_move_mail
    "send_draft" -> R.string.agent_tool_send_draft
    "remember" -> R.string.agent_tool_remember
    "forget" -> R.string.agent_tool_forget
    "fill_account_form" -> R.string.agent_tool_fill_account_form
    else -> R.string.agent_tool_unknown
}
