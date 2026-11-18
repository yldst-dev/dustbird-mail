package app.dustbird.mail

import android.app.Application
import android.content.Context
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.JakartaImapConnector
import app.dustbird.mail.core.net.JakartaSmtpSender
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.idle.IdleController
import app.dustbird.mail.notify.AndroidNotifier
import app.dustbird.mail.work.WorkScheduler
import app.dustbird.mail.core.model.SyncMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.repo.DraftRepository
import app.dustbird.mail.data.repo.MailRepository
import app.dustbird.mail.data.repo.MaintenanceRepository
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.data.secure.KeystoreCredentialStore
import app.dustbird.mail.data.secure.TokenCredentialStore
import app.dustbird.mail.sync.ImapAccess
import app.dustbird.mail.sync.MailNotifier
import app.dustbird.mail.sync.SendService
import app.dustbird.mail.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.dustbird.mail.data.repo.MailActions
import app.dustbird.mail.data.AttachmentFiles
import kotlinx.coroutines.flow.MutableStateFlow
import app.dustbird.mail.ui.nav.ComposePrefill
import app.dustbird.mail.core.agent.AgentBackend
import app.dustbird.mail.agent.MailToolbox
import app.dustbird.mail.agent.AgentService
import app.dustbird.mail.agent.InboxDigests

class AppContainer(
    private val context: Context,
    val connector: ImapConnector = JakartaImapConnector,
    val smtpSender: SmtpSender = JakartaSmtpSender,
    val credentials: CredentialStore = TokenCredentialStore(KeystoreCredentialStore(context)),
    val db: MailDatabase = MailDatabase.create(context),
    notifierFactory: (AppContainer) -> MailNotifier = { AndroidNotifier(context, it.settings) },
    agentBackend: AgentBackend? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val notifier: MailNotifier by lazy { notifierFactory(this) }
    val files = AttachmentFiles(context)
    val accounts: AccountRepository = AccountRepository(db, credentials, settings, files) { sync.forget(it) }
    val imapAccess = ImapAccess(db, credentials, connector)
    val sync: SyncService by lazy {
        SyncService(db, imapAccess, accounts, notifier, onNewMail = { accountId, rows ->
            digests.refresh()
            scope.launch { agent.onNewMail(accountId, rows) }
        })
    }
    val prefill = MutableStateFlow<ComposePrefill?>(null)
    val mail = MailRepository(db, accounts, imapAccess, files, onSeenChanged = { sync.sweepNotifications(it) }, digestsShown = settings.agent.map { it.digestsOn })
    val drafts = DraftRepository(db, imapAccess, files)
    val actions: MailActions by lazy { MailActions(db, imapAccess, scope, afterCommit = { ids -> sync.syncAccounts(ids) }) }
    val maintenance: MaintenanceRepository by lazy { MaintenanceRepository(db, credentials, settings, notifier, files) }
    val scheduler = WorkScheduler(context)
    val idle = IdleController(context)
    val send: SendService by lazy { SendService(
            db, accounts, drafts, credentials, smtpSender, connector,
            outboxDir = files.outbox,
            onQueued = { scheduler.enqueueQueue() },
            onSent = { accountId, inReplyTo -> mail.markAnswered(accountId, inReplyTo) },
            onFailed = { row, watched -> notifier.sendFailed(row.id, row.subject, watched) },
            onHeld = { accountId, sendAfter ->
                scheduler.enqueueQueueAt(sendAfter)
                val wait = sendAfter - System.currentTimeMillis()
                if (wait <= SHORT_HOLD_MILLIS) {
                    scope.launch {
                        delay(wait.coerceAtLeast(0))
                        try {
                            send.processAccount(accountId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            scheduler.enqueueQueue()
                        }
                    }
                }
            },
        ) }

    val toolbox: MailToolbox by lazy { MailToolbox(db, accounts, mail, drafts, actions, send, settings, context.resources) }
    val agent: AgentService by lazy {
        AgentService(
            db, settings, credentials, accounts, toolbox, scope,
            notifier = { title, text, sessionId -> (notifier as? AndroidNotifier)?.agentReport(title, text, sessionId) },
            backend = agentBackend,
            userAgent = "Dustbird/" + (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"),
        )
    }

    val digests: InboxDigests by lazy { InboxDigests(db, settings, mail, agent, scope) }

    fun resumeIdle() {
        scope.launch { idle.apply(accounts.accounts.first().any(::wantsIdle)) }
    }

    private fun wantsIdle(account: app.dustbird.mail.core.model.MailAccount) = account.syncMode == SyncMode.IDLE && !account.needsReauth

    fun start() {
        scope.launch {
            send.recoverAfterCrash()
            send.tidy()
            send.nextDue()?.let { scheduler.enqueueQueueAt(it) }
        }
        scope.launch { db.messages().unhideAll() }
        scope.launch {
            accounts.accounts.map { list -> list.any { it.syncMode == SyncMode.PERIODIC } }.distinctUntilChanged().collect { scheduler.applyPeriodic(it) }
        }
        scope.launch {
            accounts.accounts.map { list -> list.any(::wantsIdle) }.distinctUntilChanged().collect { idle.apply(it) }
        }
        scope.launch { notifier.prune(accounts.accounts.first().map { it.id }.toSet()) }
        scope.launch {
            agent.automations.map { list -> list.any { it.enabled && it.trigger == AgentService.TRIGGER_DAILY } }.distinctUntilChanged().collect { scheduler.applyAgent(it) }
        }
        scope.launch {
            settings.agent.map { it.digestsOn }.distinctUntilChanged().collect { if (it) digests.refresh() }
        }
    }
}

private const val SHORT_HOLD_MILLIS = 60_000L

class MailApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        app.dustbird.mail.core.mime.MimeTolerance.ensure()
        container.start()
    }
}
