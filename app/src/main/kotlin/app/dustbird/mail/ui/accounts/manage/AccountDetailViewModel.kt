package app.dustbird.mail.ui.accounts.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectionChecks
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.data.repo.RemoveAccountResult
import app.dustbird.mail.sync.AccountSyncState
import app.dustbird.mail.sync.EnqueueResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Check {
    data object Idle : Check
    data object Running : Check
    data object NoCredentials : Check
    data class Done(val imap: CheckResult, val smtp: CheckResult) : Check
}

sealed interface Reauth {
    data object Idle : Reauth
    data object Checking : Reauth
    data class Failed(val imap: CheckResult, val smtp: CheckResult) : Reauth
    data object StoreFailed : Reauth
    data object Done : Reauth
}

sealed interface TestSend {
    data object Idle : TestSend
    data object Running : TestSend
    data object Error : TestSend
    data class Result(val label: String, val state: SendState, val failure: String?) : TestSend
}

sealed interface Removal {
    data object Idle : Removal
    data object Working : Removal
    data class Blocked(val unsettledMessages: Int, val drafts: Int) : Removal
    data object Done : Removal
}

class AccountDetailViewModel(private val container: AppContainer, private val accountId: Long) : ViewModel() {
    private val whileWatched = SharingStarted.WhileSubscribed(5_000)

    val accounts: StateFlow<List<MailAccount>?> =
        container.accounts.accounts.stateIn<List<MailAccount>?>(viewModelScope, whileWatched, null)
    val identities: StateFlow<List<MailIdentity>> =
        container.accounts.identities.map { all -> all.filter { it.accountId == accountId } }.stateIn(viewModelScope, whileWatched, emptyList())
    val appDefaultIdentityId: StateFlow<Long?> = container.accounts.appDefaultIdentityId.stateIn(viewModelScope, whileWatched, null)
    val folders: StateFlow<List<FolderEntity>?> =
        container.db.folders().observeForAccount(accountId).stateIn<List<FolderEntity>?>(viewModelScope, whileWatched, null)
    val syncState: StateFlow<AccountSyncState?> = container.sync.state.map { it[accountId] }.stateIn(viewModelScope, whileWatched, null)

    private val _check = MutableStateFlow<Check>(Check.Idle)
    val check: StateFlow<Check> = _check.asStateFlow()

    private val _reauth = MutableStateFlow<Reauth>(Reauth.Idle)
    val reauth: StateFlow<Reauth> = _reauth.asStateFlow()

    private val _testSend = MutableStateFlow<TestSend>(TestSend.Idle)
    val testSend: StateFlow<TestSend> = _testSend.asStateFlow()

    private val _removal = MutableStateFlow<Removal>(Removal.Idle)
    val removal: StateFlow<Removal> = _removal.asStateFlow()

    fun recheck() {
        if (_check.value == Check.Running) return
        _check.value = Check.Running
        container.scope.launch {
            val account = container.accounts.account(accountId)
            val credentials = runCatching { container.accounts.credentialsFor(accountId) }.getOrNull()
            if (account == null || credentials == null) {
                _check.value = Check.NoCredentials
                return@launch
            }
            val (imap, smtp) = runChecks(account, credentials)
            container.accounts.updateStages(accountId) {
                it.copy(
                    imapAuth = AccountRepository.stageOk(imap is CheckResult.Ok),
                    smtpAuth = AccountRepository.stageOk(smtp is CheckResult.Ok),
                )
            }
            _check.value = Check.Done(imap, smtp)
        }
    }

    fun reauthenticate(secret: String) {
        if (_reauth.value == Reauth.Checking) return
        _reauth.value = Reauth.Checking
        container.scope.launch {
            val account = container.accounts.account(accountId)
            if (account == null) {
                _reauth.value = Reauth.StoreFailed
                return@launch
            }
            val (imap, smtp) = runChecks(account, Credentials(account.username, secret))
            _reauth.value = if (imap !is CheckResult.Ok) {
                Reauth.Failed(imap, smtp)
            } else {
                try {
                    container.accounts.reauthenticate(
                        accountId,
                        secret,
                        ConnectionStages(imapAuth = StageState.OK, smtpAuth = AccountRepository.stageOk(smtp is CheckResult.Ok)),
                    )
                    Reauth.Done
                } catch (_: Exception) {
                    Reauth.StoreFailed
                }
            }
        }
    }

    fun testSend(identity: MailIdentity, subject: String, body: String) {
        if (_testSend.value == TestSend.Running) return
        _testSend.value = TestSend.Running
        container.scope.launch {
            _testSend.value = try {
                val draftId = container.drafts.save(
                    DraftContent(
                        id = null,
                        identityId = identity.id,
                        to = listOf(EmailAddress(identity.address)),
                        cc = emptyList(),
                        bcc = emptyList(),
                        subject = subject,
                        body = body,
                        inReplyTo = null,
                        references = emptyList(),
                    ),
                )
                when (val queued = container.send.enqueue(draftId)) {
                    is EnqueueResult.Queued -> {
                        container.send.processAccount(accountId)
                        val item = container.send.items.first().firstOrNull { it.id == queued.outboxId }
                        if (item == null) TestSend.Error else TestSend.Result(identity.label, item.state, item.failure)
                    }
                    else -> {
                        container.drafts.delete(draftId)
                        TestSend.Error
                    }
                }
            } catch (_: Exception) {
                TestSend.Error
            }
        }
    }

    fun remove(force: Boolean) {
        if (_removal.value == Removal.Working) return
        _removal.value = Removal.Working
        container.scope.launch {
            _removal.value = when (val result = container.accounts.removeAccount(accountId, force)) {
                RemoveAccountResult.Removed -> Removal.Done
                is RemoveAccountResult.Blocked -> Removal.Blocked(result.unsettledMessages, result.drafts)
            }
        }
    }

    fun cancelRemoval() {
        if (_removal.value is Removal.Blocked) _removal.value = Removal.Idle
    }

    private suspend fun runChecks(account: MailAccount, credentials: Credentials): Pair<CheckResult, CheckResult> = coroutineScope {
        val imap = async(Dispatchers.IO) { ConnectionChecks.imap(container.connector, account.imap, credentials) }
        val smtp = async(Dispatchers.IO) { ConnectionChecks.smtp(account.smtp, credentials) }
        imap.await() to smtp.await()
    }
}
