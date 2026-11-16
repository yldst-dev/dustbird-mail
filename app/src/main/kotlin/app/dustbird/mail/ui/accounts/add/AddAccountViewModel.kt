package app.dustbird.mail.ui.accounts.add

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ConnectionChecks
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.NewAccount
import app.dustbird.mail.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY_STEP = "add_account_step"
private const val KEY_PROVIDER = "add_account_provider"
private const val KEY_NAME = "add_account_name"
private const val KEY_ACCOUNT = "add_account_account"
private const val DETAIL_LIMIT = 300
private val syncedRoles = listOf(FolderRole.SENT, FolderRole.DRAFTS, FolderRole.SPAM)

class AddAccountViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val imapCheck: (ServerEndpoint, Credentials) -> CheckResult = { endpoint, credentials ->
        ConnectionChecks.imap(container.connector, endpoint, credentials)
    },
    private val smtpCheck: (ServerEndpoint, Credentials) -> CheckResult = ConnectionChecks::smtp,
) : ViewModel() {
    val fields = AddAccountFields()

    private val _state = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

    private var verifyJob: Job? = null
    private var pending: NewAccount? = null
    private var autoUsername = ""

    init {
        restore()
        viewModelScope.launch {
            snapshotFlow { fields.address.text.trim().toString() }.collect { followAddress(it) }
        }
    }

    override fun onCleared() {
        fields.password.clearText()
    }

    fun choose(providerId: String, suggestedName: String) {
        if (_state.value.providerId != providerId) {
            val profile = Providers.byId(providerId)
            autoUsername = ""
            fields.reset(profile, if (profile.imap == null) "" else suggestedName)
            handle[KEY_NAME] = suggestedName
            set { copy(providerId = providerId, acknowledged = false, showErrors = false, saveError = null) }
        }
        set { copy(step = AddStep.PREPARE) }
    }

    fun acknowledge(value: Boolean) = set { copy(acknowledged = value) }

    fun proceed() {
        if (_state.value.acknowledged) set { copy(step = AddStep.FORM) }
    }

    fun back() {
        val current = _state.value
        if (current.busy) return
        when (current.step) {
            AddStep.CHOOSE -> Unit
            AddStep.PREPARE -> set { copy(step = AddStep.CHOOSE) }
            AddStep.FORM -> set { copy(step = AddStep.PREPARE) }
            AddStep.VERIFY -> backToForm()
            AddStep.SYNC, AddStep.ROLES -> finish()
        }
    }

    fun verify() {
        if (verifyJob?.isActive == true) return
        val values = fields.values()
        val invalid = FormRules.invalidFields(values)
        val providerId = _state.value.providerId ?: return
        if (invalid.isNotEmpty()) {
            if (invalid.any { it in advancedFields }) fields.advancedOpen = true
            set { copy(showErrors = true) }
            return
        }
        val new = NewAccount(
            providerId = providerId,
            displayName = values.displayName,
            label = values.label,
            username = values.username,
            loginAddress = values.address,
            identityDisplayName = values.identityName,
            imap = ServerEndpoint(values.imapHost, FormRules.parsePort(values.imapPort)!!, values.imapSecurity),
            smtp = ServerEndpoint(values.smtpHost, FormRules.parsePort(values.smtpPort)!!, values.smtpSecurity),
        )
        pending = new
        val secret = values.password
        val credentials = Credentials(new.username, secret)
        set { copy(step = AddStep.VERIFY, imap = Stage.Running, smtp = Stage.Pending, saveError = null) }
        verifyJob = viewModelScope.launch {
            val imap = withContext(Dispatchers.IO) { imapCheck(new.imap, credentials) }.scrubbed(secret)
            set { copy(imap = Stage.Done(imap)) }
            if (imap is CheckResult.Ok) {
                set { copy(smtp = Stage.Running) }
                val smtp = withContext(Dispatchers.IO) { smtpCheck(new.smtp, credentials) }.scrubbed(secret)
                set { copy(smtp = Stage.Done(smtp)) }
            }
        }
    }

    fun backToForm() {
        verifyJob?.cancel()
        pending = null
        val failure = (_state.value.imap as? Stage.Done)?.result as? CheckResult.Failed
        if (failure?.failure == ConnectFailure.AUTH && _state.value.providerId == Providers.ICLOUD.id) fields.advancedOpen = true
        set { copy(step = AddStep.FORM, imap = Stage.Pending, smtp = Stage.Pending, saveError = null) }
    }

    fun save() {
        val new = pending ?: return
        val current = _state.value
        if (current.busy || !current.imap.passed || !current.smtp.passed) return
        set { copy(busy = true, saveError = null) }
        viewModelScope.launch {
            val result = try {
                container.accounts.addAccount(
                    new,
                    fields.password.text.toString(),
                    ConnectionStages(imapAuth = StageState.OK, smtpAuth = StageState.OK),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            when (result) {
                is AddAccountResult.Added -> {
                    fields.password.clearText()
                    pending = null
                    set { copy(busy = false, step = AddStep.SYNC, accountId = result.accountId, sync = null) }
                    startSync(result.accountId)
                }
                is AddAccountResult.Rejected -> set { copy(busy = false, saveError = SaveError.Rejected(result.reason)) }
                null -> set { copy(busy = false, saveError = SaveError.Storage) }
            }
        }
    }

    fun retrySync() {
        val id = _state.value.accountId ?: return
        if (_state.value.sync == null) return
        set { copy(sync = null, roles = emptyList()) }
        startSync(id)
    }

    fun continueAfterSync() {
        if (_state.value.roles.isEmpty()) finish() else set { copy(step = AddStep.ROLES) }
    }

    fun selectRole(role: FolderRole, path: String?) =
        set { copy(roles = roles.map { if (it.role == role) it.copy(selected = path) else it }) }

    fun confirmRoles() {
        val current = _state.value
        val id = current.accountId ?: return
        if (current.busy) return
        set { copy(busy = true) }
        viewModelScope.launch {
            current.roles.forEach { prompt -> prompt.selected?.let { container.sync.confirmRole(id, prompt.role, it) } }
            set { copy(busy = false, finished = true) }
        }
    }

    fun finish() = set { copy(finished = true) }

    private fun startSync(accountId: Long) {
        container.scope.launch {
            val outcome = container.sync.syncAccount(accountId)
            val roles = if (outcome == SyncOutcome.Synced) rolePrompts(accountId) else emptyList()
            withContext(Dispatchers.Main) { set { copy(sync = outcome, roles = roles) } }
        }
    }

    private suspend fun rolePrompts(accountId: Long): List<RolePrompt> {
        val folders = container.db.folders().observeForAccount(accountId).first()
        val infos = folders.map { entity ->
            FolderInfo(
                path = entity.path,
                delimiter = entity.delimiter?.firstOrNull(),
                attributes = entity.attributes.split(' ').filter { it.isNotEmpty() }.toSet(),
            )
        }
        val confirmed = folders.filter { it.roleConfirmed && it.role != null }.associate { it.role!! to it.path }
        val resolved = FolderRoles.resolve(infos, confirmed)
        val others = infos.filter { it.selectable && !FolderRoles.isInbox(it.path) }.map { it.path }
        return syncedRoles.mapNotNull { role ->
            when (val resolution = resolved.getValue(role)) {
                is RoleResolution.Assigned -> null
                is RoleResolution.NeedsConfirmation -> RolePrompt(role, resolution.candidates, missing = false)
                RoleResolution.Missing -> others.takeIf { it.isNotEmpty() }?.let { RolePrompt(role, it, missing = true) }
            }
        }
    }

    private fun followAddress(address: String) {
        val profile = _state.value.providerId?.let(Providers::byId) ?: return
        val next = Providers.usernameFor(profile, address)
        if (fields.username.text.toString() == autoUsername) fields.username.setTextAndPlaceCursorAtEnd(next)
        autoUsername = next
    }

    private fun restore() {
        val providerId = handle.get<String>(KEY_PROVIDER) ?: return
        val step = handle.get<String>(KEY_STEP)?.let { name -> AddStep.entries.firstOrNull { it.name == name } } ?: return
        val accountId = handle.get<Long>(KEY_ACCOUNT)
        val restored = when {
            accountId != null && (step == AddStep.SYNC || step == AddStep.ROLES) -> AddStep.SYNC
            step == AddStep.PREPARE -> AddStep.PREPARE
            step == AddStep.CHOOSE -> AddStep.CHOOSE
            else -> AddStep.FORM
        }
        val profile = Providers.byId(providerId)
        fields.reset(profile, if (profile.imap == null) "" else handle.get<String>(KEY_NAME).orEmpty())
        _state.value = AddAccountUiState(step = restored, providerId = providerId, accountId = accountId, acknowledged = restored == AddStep.FORM)
        if (restored == AddStep.SYNC && accountId != null) startSync(accountId)
    }

    private fun set(transform: AddAccountUiState.() -> AddAccountUiState) {
        val next = _state.updateAndGet { it.transform() }
        handle[KEY_STEP] = next.step.name
        handle[KEY_PROVIDER] = next.providerId
        handle[KEY_ACCOUNT] = next.accountId
    }

    private fun CheckResult.scrubbed(secret: String): CheckResult =
        if (this is CheckResult.Failed) copy(detail = detail.replace(secret, "***").take(DETAIL_LIMIT)) else this
}
