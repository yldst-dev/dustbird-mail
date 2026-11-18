package app.dustbird.mail.agent

import android.util.Log
import app.dustbird.mail.core.agent.AgentBackend
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentListener
import app.dustbird.mail.core.agent.AgentPrompts
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.AgentRunner
import app.dustbird.mail.core.agent.ApprovalGate
import app.dustbird.mail.core.agent.ApprovalMode
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.core.agent.ToolInvocation
import app.dustbird.mail.core.agent.ToolOutcome
import app.dustbird.mail.core.agent.ToolSpec
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.AgentSettings
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.data.db.AgentAutomationEntity
import app.dustbird.mail.data.db.AgentItemEntity
import app.dustbird.mail.data.db.AgentLogEntity
import app.dustbird.mail.data.db.AgentSessionEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.secure.CredentialStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LoginFailure { EXPIRED, NETWORK, REJECTED }

sealed interface LoginState {
    data object Idle : LoginState
    data object Starting : LoginState
    data class Code(val userCode: String, val url: String, val expiresAt: Long) : LoginState
    data object Working : LoginState
    data object Done : LoginState
    data class Failed(val failure: LoginFailure) : LoginState
}

class PendingApproval(val id: Long, val sessionId: Long, val call: ToolInvocation, val preview: ToolPreview, val answer: CompletableDeferred<Boolean>)

data class AgentRunError(val failure: BackendFailure, val detail: String)

fun interface AgentNotifier {
    fun report(title: String, text: String, sessionId: Long)
}

class AgentService(
    private val db: MailDatabase,
    private val settings: SettingsRepository,
    private val secrets: CredentialStore,
    private val accounts: AccountRepository,
    private val toolbox: MailToolbox,
    private val scope: CoroutineScope,
    private val notifier: AgentNotifier = AgentNotifier { _, _, _ -> },
    backend: AgentBackend? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = "Dustbird",
    private val authIssuer: String = CodexAuth.ISSUER,
    private val codexBase: String = CodexAuth.BASE_URL,
    private val codexRelease: String = CodexAuth.LATEST_RELEASE,
) {
    private val backend: AgentBackend = backend ?: ResponsesBackend(::config)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val approvalIds = AtomicLong(1)
    private val approvalLock = Mutex()
    private val automationLock = Mutex()
    private var loginJob: Job? = null

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running
    private val _partial = MutableStateFlow<Map<Long, String>>(emptyMap())
    val partial: StateFlow<Map<Long, String>> = _partial
    private val _errors = MutableStateFlow<Map<Long, AgentRunError>>(emptyMap())
    val errors: StateFlow<Map<Long, AgentRunError>> = _errors
    private val _login = MutableStateFlow<LoginState>(LoginState.Idle)
    val login: StateFlow<LoginState> = _login

    val current: Flow<AgentSettings> = settings.agent
    val sessions: Flow<List<AgentSessionEntity>> = db.agent().observeSessions(30)
    val memory = db.agent().observeMemory()
    val automations = db.agent().observeAutomations()
    val log = db.agent().observeLog(60)

    fun items(sessionId: Long): Flow<List<AgentItemEntity>> = db.agent().observeItems(sessionId)

    fun hasSecret(): Boolean = secrets.get(SECRET_ID) != null

    fun chatGptAccount(): String? = credential()?.let { it.email ?: it.accountId.orEmpty() }

    suspend fun saveConnection(mode: AgentMode, baseUrl: String, model: String, secret: String?) {
        if (!secret.isNullOrBlank()) withContext(Dispatchers.IO) { secrets.put(SECRET_ID, secret.trim()) }
        settings.setAgentModels(mode, "")
        update { it.copy(mode = mode, baseUrl = baseUrl.trim(), model = model.trim()) }
    }

    suspend fun update(transform: (AgentSettings) -> AgentSettings) = settings.updateAgent { old ->
        val next = transform(old)
        if (next.mode != old.mode && next.model == old.model) next.copy(model = "") else next
    }

    fun models(mode: AgentMode): Flow<List<AgentModel>> = settings.agentModels(mode).map(AgentModel::fromStored)

    fun automaticModel(mode: AgentMode, models: List<AgentModel>): String = when (mode) {
        AgentMode.CHATGPT -> models.firstOrNull()?.slug ?: DEFAULT_MODEL
        AgentMode.GATEWAY -> GATEWAY_MODEL
        AgentMode.API_KEY, AgentMode.OFF -> DEFAULT_MODEL
    }

    suspend fun ready(): Boolean = config() != null

    suspend fun config(): BackendConfig? {
        val value = settings.agent.first()
        if (!value.consent) return null
        return when (value.mode) {
            AgentMode.OFF -> null
            AgentMode.API_KEY -> {
                val base = value.baseUrl.ifBlank { OPENAI_BASE }
                if (!safeBase(base)) null else secrets.get(SECRET_ID)?.let { BackendConfig(base, it, value.model.ifBlank { DEFAULT_MODEL }, encryptedReasoning = base == OPENAI_BASE) }
            }
            AgentMode.GATEWAY ->
                if (!safeBase(value.baseUrl)) null else secrets.get(SECRET_ID)?.let { BackendConfig(value.baseUrl, it, value.model.ifBlank { GATEWAY_MODEL }) }
            AgentMode.CHATGPT -> fresh()?.let {
                val model = value.model.ifBlank { automaticModel(AgentMode.CHATGPT, models(AgentMode.CHATGPT).first()) }
                BackendConfig(codexBase, it.accessToken, model, CodexAuth.headers(it, userAgent), encryptedReasoning = true)
            }
        }
    }

    suspend fun syncModels(mode: AgentMode): List<AgentModel> {
        val models = try {
            when (mode) {
                AgentMode.OFF -> return emptyList()
                AgentMode.CHATGPT -> {
                    val credential = fresh() ?: throw AgentBackendException(BackendFailure.AUTH, "Not signed in to ChatGPT")
                    val version = codexVersion()
                    withContext(Dispatchers.IO) { CodexAuth.models(credential, userAgent, version, codexBase) }
                }
                AgentMode.API_KEY, AgentMode.GATEWAY -> {
                    val value = settings.agent.first()
                    val base = if (mode == AgentMode.API_KEY) value.baseUrl.ifBlank { OPENAI_BASE } else value.baseUrl
                    val token = secrets.get(SECRET_ID)
                    if (!safeBase(base) || token == null) throw AgentBackendException(BackendFailure.NOT_CONFIGURED, "The connection is not saved")
                    withContext(Dispatchers.IO) { ModelLists.compatible(base, token, userAgent, official = base == OPENAI_BASE) }
                }
            }
        } catch (_: AuthException) {
            throw AgentBackendException(BackendFailure.AUTH, "The model list was refused")
        } catch (_: java.io.IOException) {
            throw AgentBackendException(BackendFailure.NETWORK, "The model list could not be loaded")
        }
        if (models.isEmpty()) throw AgentBackendException(BackendFailure.SERVER, "The model list was empty")
        settings.setAgentModels(mode, AgentModel.stored(models))
        return models
    }

    private suspend fun codexVersion(): String {
        val latest = withContext(Dispatchers.IO) {
            try {
                CodexAuth.latestVersion(userAgent, codexRelease)
            } catch (_: java.io.IOException) {
                null
            }
        }
        if (latest != null) {
            settings.setCodexVersion(latest)
            return latest
        }
        return settings.codexVersion.first().takeIf(CodexAuth::isVersion) ?: CodexAuth.FALLBACK_VERSION
    }

    suspend fun createSession(source: String = SOURCE_CHAT, title: String = ""): Long {
        val now = clock()
        db.agent().pruneSessions(now - KEEP_MILLIS)
        db.agent().pruneLog(now - KEEP_MILLIS)
        return db.agent().insertSession(AgentSessionEntity(title = title, source = source, createdAt = now, updatedAt = now))
    }

    suspend fun deleteSession(sessionId: Long) {
        stop(sessionId)
        db.agent().deleteSession(sessionId)
    }

    fun send(sessionId: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || sessionId in _running.value) return
        _running.update { it + sessionId }
        _errors.update { it - sessionId }
        jobs[sessionId] = scope.launch {
            try {
                val session = db.agent().session(sessionId) ?: return@launch
                insert(sessionId, AgentItem.User(clean))
                db.agent().touchSession(sessionId, session.title.ifBlank { clean.substringBefore('\n').take(40) }, clock())
                run(sessionId, SOURCE_CHAT, unattended = false, allowChanges = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentBackendException) {
                report(SOURCE_CHAT, error)
                _errors.update { it + (sessionId to AgentRunError(error.failure, error.message.orEmpty())) }
            } catch (error: Exception) {
                report(SOURCE_CHAT, error)
                _errors.update { it + (sessionId to AgentRunError(BackendFailure.SERVER, describe(error))) }
            } finally {
                _partial.update { it - sessionId }
                _running.update { it - sessionId }
                jobs.remove(sessionId)
            }
        }
    }

    suspend fun extract(source: String, instructions: String, text: String, tool: ToolSpec): String {
        val call = try {
            backend.respond(AgentRequest(instructions, listOf(AgentItem.User(text)), listOf(tool))) {}
                .firstNotNullOfOrNull { item -> item.call?.takeIf { it.name == tool.name } }
                ?: throw AgentBackendException(BackendFailure.INCOMPLETE, "The model did not call ${tool.name}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(source, error)
            throw error
        }
        db.agent().insertLog(AgentLogEntity(at = clock(), source = source, tool = tool.name, summary = "", outcome = ToolOutcome.DONE.name, approval = "USER"))
        return call.arguments
    }

    fun stop(sessionId: Long) {
        jobs.remove(sessionId)?.cancel()
        _approval.value?.takeIf { it.sessionId == sessionId }?.answer?.complete(false)
    }

    fun answer(approvalId: Long, approved: Boolean) {
        _approval.value?.takeIf { it.id == approvalId }?.answer?.complete(approved)
    }

    suspend fun onNewMail(accountId: Long, messageRowIds: List<Long>) {
        if (messageRowIds.isEmpty() || accountId in settings.agent.first().blockedAccounts) return
        val automations = db.agent().enabledAutomations(TRIGGER_NEW_MAIL)
        if (automations.isEmpty() || !ready()) return
        val ids = messageRowIds.take(10).joinToString(", ")
        automations.forEach { automate(it, "New mail arrived in account $accountId. Mail ids: $ids.\nTask from the user: ${it.instruction}") }
    }

    suspend fun runDue() {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(clock()).atZone(zone)
        val dayStart = LocalDate.from(now).atStartOfDay(zone).toInstant().toEpochMilli()
        val due = db.agent().enabledAutomations(TRIGGER_DAILY).filter { it.hour <= now.hour && it.lastRunAt < dayStart }
        if (due.isEmpty() || !ready()) return
        due.forEach { automate(it, "This is the scheduled daily run.\nTask from the user: ${it.instruction}") }
    }

    suspend fun saveAutomation(entity: AgentAutomationEntity): Long = db.agent().upsertAutomation(entity)

    suspend fun deleteAutomation(id: Long) = db.agent().deleteAutomation(id)

    suspend fun deleteMemory(id: Long) = db.agent().deleteMemory(id)

    private suspend fun automate(automation: AgentAutomationEntity, prompt: String) = automationLock.withLock {
        val sessionId = createSession("$SOURCE_AUTOMATION${automation.id}", automation.name)
        try {
            insert(sessionId, AgentItem.User(prompt))
            run(sessionId, automation.name, unattended = true, allowChanges = automation.allowChanges)
            val answer = db.agent().items(sessionId).lastOrNull { it.kind == KIND_MODEL && !it.text.isNullOrBlank() }?.text
            if (answer != null) notifier.report(automation.name, answer, sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(automation.name, error)
            _errors.update { it + (sessionId to AgentRunError((error as? AgentBackendException)?.failure ?: BackendFailure.SERVER, describe(error))) }
        } finally {
            db.agent().markAutomationRun(automation.id, clock())
            _partial.update { it - sessionId }
        }
    }

    private suspend fun run(sessionId: Long, source: String, unattended: Boolean, allowChanges: Boolean) {
        val value = settings.agent.first()
        val allowed = accounts.accountsWithIdentities.first().filter { it.account.id !in value.blockedAccounts }
        val accountLines = allowed.joinToString("\n") { group ->
            "- account_id ${group.account.id}: ${group.account.displayName} (${group.identities.joinToString(", ") { it.address }})"
        }
        val instructions = AgentPrompts.instructions(
            today = LocalDate.now().toString(),
            accounts = accountLines,
            standing = value.instructions,
            memory = db.agent().memory().map { "[${it.id}] ${it.text}" },
            unattended = unattended,
        )
        val mode = when {
            unattended -> if (allowChanges) ApprovalMode.AUTO_CHANGES else ApprovalMode.ASK_CHANGES
            value.autoChanges -> ApprovalMode.AUTO_CHANGES
            else -> ApprovalMode.ASK_CHANGES
        }
        val gate = if (unattended) ApprovalGate { _, _ -> false } else ApprovalGate { call, _ -> ask(sessionId, call) }
        val listener = object : AgentListener {
            override fun onTextDelta(delta: String) = _partial.update { it + (sessionId to it[sessionId].orEmpty() + delta) }

            override suspend fun onItem(item: AgentItem) {
                insert(sessionId, item)
                if (item is AgentItem.Model) _partial.update { it - sessionId }
            }

            override suspend fun onToolFinished(call: ToolInvocation, outcome: ToolOutcome, approvedByUser: Boolean) {
                db.agent().insertLog(
                    AgentLogEntity(
                        at = clock(),
                        source = source,
                        tool = call.name,
                        summary = call.arguments.take(300),
                        outcome = outcome.name,
                        approval = when {
                            approvedByUser -> "USER"
                            outcome == ToolOutcome.DENIED -> if (unattended) "UNATTENDED" else "USER"
                            else -> "AUTO"
                        },
                    ),
                )
            }
        }
        val history = db.agent().items(sessionId).mapNotNull(::toItem)
        AgentRunner(backend, toolbox.specs, toolbox::execute, mode, gate).run(instructions, history, listener)
        db.agent().session(sessionId)?.let { db.agent().touchSession(sessionId, it.title, clock()) }
    }

    private suspend fun ask(sessionId: Long, call: ToolInvocation): Boolean = approvalLock.withLock {
        val pending = PendingApproval(approvalIds.getAndIncrement(), sessionId, call, toolbox.preview(call), CompletableDeferred())
        _approval.value = pending
        try {
            pending.answer.await()
        } finally {
            _approval.compareAndSet(pending, null)
        }
    }

    private suspend fun insert(sessionId: Long, item: AgentItem) {
        val now = clock()
        val entity = when (item) {
            is AgentItem.User -> AgentItemEntity(sessionId = sessionId, kind = KIND_USER, raw = null, text = item.text, callId = null, toolName = null, arguments = null, output = null, createdAt = now)
            is AgentItem.Model -> AgentItemEntity(
                sessionId = sessionId, kind = KIND_MODEL, raw = item.raw, text = item.text, callId = item.call?.callId,
                toolName = item.call?.name, arguments = item.call?.arguments, output = null, createdAt = now,
            )
            is AgentItem.ToolOutput -> AgentItemEntity(sessionId = sessionId, kind = KIND_OUTPUT, raw = null, text = null, callId = item.callId, toolName = null, arguments = null, output = item.output, createdAt = now)
        }
        db.agent().insertItem(entity)
    }

    private fun toItem(entity: AgentItemEntity): AgentItem? = when (entity.kind) {
        KIND_USER -> AgentItem.User(entity.text.orEmpty())
        KIND_MODEL -> AgentItem.Model(
            raw = entity.raw.orEmpty(),
            text = entity.text,
            call = entity.callId?.let { ToolInvocation(it, entity.toolName.orEmpty(), entity.arguments.orEmpty()) },
        )
        KIND_OUTPUT -> AgentItem.ToolOutput(entity.callId.orEmpty(), entity.output.orEmpty())
        else -> null
    }

    fun startLogin() {
        if (loginJob?.isActive == true) return
        _login.value = LoginState.Starting
        loginJob = scope.launch(Dispatchers.IO) {
            try {
                val code = CodexAuth.requestCode(clock(), userAgent, authIssuer)
                _login.value = LoginState.Code(code.userCode, CodexAuth.verificationUrl(authIssuer), code.expiresAt)
                while (true) {
                    delay(code.intervalMillis)
                    if (clock() >= code.expiresAt) {
                        _login.value = LoginState.Failed(LoginFailure.EXPIRED)
                        return@launch
                    }
                    val grant = try {
                        CodexAuth.poll(code, userAgent, authIssuer)
                    } catch (error: AuthException) {
                        throw error
                    } catch (_: java.io.IOException) {
                        null
                    } ?: continue
                    _login.value = LoginState.Working
                    secrets.put(CHATGPT_ID, CodexAuth.exchange(grant, clock(), userAgent, authIssuer).toJson())
                    update { it.copy(mode = AgentMode.CHATGPT) }
                    _login.value = LoginState.Done
                    try {
                        syncModels(AgentMode.CHATGPT)
                    } catch (_: AgentBackendException) {
                    }
                    return@launch
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: AuthException) {
                _login.value = LoginState.Failed(LoginFailure.REJECTED)
            } catch (_: Exception) {
                _login.value = LoginState.Failed(LoginFailure.NETWORK)
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _login.value = LoginState.Idle
    }

    fun acknowledgeLogin() {
        if (_login.value is LoginState.Done || _login.value is LoginState.Failed) _login.value = LoginState.Idle
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) { secrets.delete(CHATGPT_ID) }
        settings.setAgentModels(AgentMode.CHATGPT, "")
        update { if (it.mode == AgentMode.CHATGPT) it.copy(mode = AgentMode.OFF) else it }
    }

    private fun credential(): ChatGptCredential? = secrets.get(CHATGPT_ID)?.let(ChatGptCredential::fromJson)

    private suspend fun fresh(): ChatGptCredential? {
        val known = credential() ?: return null
        if (known.expiresAt - clock() > 60_000) return known
        return try {
            withContext(Dispatchers.IO) { CodexAuth.refresh(known, clock(), userAgent, authIssuer).also { secrets.put(CHATGPT_ID, it.toJson()) } }
        } catch (_: AuthException) {
            null
        } catch (_: java.io.IOException) {
            throw AgentBackendException(BackendFailure.NETWORK, "The sign-in could not be refreshed")
        }
    }

    private fun safeBase(url: String): Boolean = isSafeBase(url)

    private fun report(source: String, error: Exception) {
        val failure = (error as? AgentBackendException)?.failure?.name ?: "UNEXPECTED"
        Log.w(LOG_TAG, "Agent request from $source failed: $failure ${describe(error)}")
    }

    companion object {
        fun describe(error: Throwable): String =
            (if (error is AgentBackendException) error.message.orEmpty() else "${error.javaClass.simpleName}: ${error.message.orEmpty()}").take(300)

        fun isSafeBase(url: String): Boolean =
            url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

        private const val LOG_TAG = "Dustbird"
        const val SECRET_ID = -1L
        const val CHATGPT_ID = -2L
        const val OPENAI_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        const val GATEWAY_MODEL = "openclaw"
        const val SOURCE_CHAT = "chat"
        const val SOURCE_AUTOMATION = "auto:"
        const val TRIGGER_NEW_MAIL = "NEW_MAIL"
        const val TRIGGER_DAILY = "DAILY"
        const val KIND_USER = "USER"
        const val KIND_MODEL = "MODEL"
        const val KIND_OUTPUT = "OUTPUT"
        private const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
