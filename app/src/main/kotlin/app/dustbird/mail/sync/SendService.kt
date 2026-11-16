package app.dustbird.mail.sync

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.core.send.FailureCodes
import app.dustbird.mail.core.send.MessageIds
import app.dustbird.mail.core.send.OutboxItem
import app.dustbird.mail.core.send.OutboxStore
import app.dustbird.mail.core.send.SendEvent
import app.dustbird.mail.core.send.SendMachine
import app.dustbird.mail.core.send.SendOrchestrator
import app.dustbird.mail.core.send.SendRecord
import app.dustbird.mail.core.send.SendRoute
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.Codec
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.db.OutboxEntity
import app.dustbird.mail.data.repo.AccountRepository
import app.dustbird.mail.data.repo.DraftRepository
import app.dustbird.mail.data.secure.CredentialStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EnqueueResult {
    data class Queued(val outboxId: Long) : EnqueueResult
    data object NoRecipients : EnqueueResult
    data class InvalidAddress(val address: String) : EnqueueResult
    data object DraftMissing : EnqueueResult
}

class SendService(
    private val db: MailDatabase,
    private val accounts: AccountRepository,
    private val drafts: DraftRepository,
    private val credentials: CredentialStore,
    smtp: SmtpSender,
    imap: ImapConnector,
    private val onQueued: () -> Unit = {},
    pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    val items: Flow<List<OutboxEntity>> = db.outbox().observeAll()
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val store = OutboxStore { id, record ->
        db.sync().setOutboxState(id, record.state, record.attempts, record.failure, System.currentTimeMillis())
    }
    private val orchestrator = SendOrchestrator(smtp, imap, store, pause)

    suspend fun enqueue(draftId: Long): EnqueueResult {
        val draft = db.drafts().get(draftId) ?: return EnqueueResult.DraftMissing
        val identity = db.identities().get(draft.identityId) ?: return EnqueueResult.DraftMissing
        val to = Codec.parseAddresses(draft.toAddresses)
        val cc = Codec.parseAddresses(draft.ccAddresses)
        val bcc = Codec.parseAddresses(draft.bccAddresses)
        val everyone = to + cc + bcc
        if (everyone.isEmpty()) return EnqueueResult.NoRecipients
        everyone.firstOrNull { !Addresses.isValid(it.address) }?.let { return EnqueueResult.InvalidAddress(it.address) }
        val messageId = MessageIds.generate(identity.address)
        val composed = MimeComposer.compose(
            OutgoingMessage(
                from = EmailAddress(identity.address, identity.displayName.ifBlank { null }),
                to = to,
                cc = cc,
                bcc = bcc,
                subject = draft.subject,
                text = draft.body,
                messageId = messageId,
                inReplyTo = draft.inReplyTo,
                references = Codec.parseStrings(draft.references),
                sentAt = Instant.now(),
            ),
        )
        val now = System.currentTimeMillis()
        val id = db.outbox().insert(
            OutboxEntity(
                accountId = draft.accountId,
                identityId = identity.id,
                state = SendState.QUEUED,
                attempts = 0,
                failure = null,
                messageId = messageId,
                subject = draft.subject,
                recipientSummary = everyone.joinToString(", ") { it.name ?: it.address },
                raw = composed.raw,
                rawWithBcc = composed.rawWithBcc,
                envelopeFrom = composed.envelopeFrom,
                recipients = composed.recipients.joinToString("\n"),
                createdAt = now,
                updatedAt = now,
            ),
        )
        drafts.delete(draftId)
        onQueued()
        return EnqueueResult.Queued(id)
    }

    suspend fun recoverAfterCrash() {
        db.outbox().sending().forEach {
            val next = SendMachine.next(SendRecord(it.state, it.attempts, it.failure), SendEvent.RecoverAfterCrash)
            store.save(it.id, next)
        }
    }

    suspend fun processAll() = db.accounts().getAll().forEach { processAccount(it.id) }

    suspend fun processAccount(accountId: Long) = lock(accountId).withLock {
        db.outbox().byAccountAndStates(accountId, listOf(SendState.QUEUED)).forEach { send(it) }
    }

    suspend fun hasQueued(): Boolean = db.accounts().getAll().any { db.outbox().byAccountAndStates(it.id, listOf(SendState.QUEUED)).isNotEmpty() }

    suspend fun retry(id: Long) = transition(id, SendEvent.UserRetry) { processAccount(it.accountId) }

    suspend fun resend(id: Long) = transition(id, SendEvent.UserResend) { processAccount(it.accountId) }

    suspend fun checkUnknown(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.resolveUnknown(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun confirmSent(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.saveAfterConfirmation(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun retrySave(id: Long) = withItem(id) { entity, account, route ->
        orchestrator.retrySave(toItem(entity), route).also { afterRecord(entity, account, it, outcomeCode = null) }
    }

    suspend fun discard(id: Long) {
        val entity = db.outbox().get(id) ?: return
        if (entity.state in setOf(SendState.FAILED, SendState.UNKNOWN, SendState.SAVED, SendState.SAVE_FAILED)) db.outbox().delete(id)
    }

    private suspend fun send(entity: OutboxEntity) {
        val account = db.accounts().get(entity.accountId) ?: return
        val route = route(account)
        if (route == null) {
            val began = SendMachine.next(SendRecord(entity.state, entity.attempts, entity.failure), SendEvent.Begin)
            store.save(entity.id, began)
            store.save(entity.id, SendMachine.next(began, SendEvent.SmtpRejected(FailureCodes.rejected(RejectKind.AUTH, null))))
            accounts.markNeedsReauth(account.id)
            return
        }
        val report = withContext(Dispatchers.IO) { orchestrator.send(toItem(entity), route) }
        val outcomeCode = report.record.failure
        afterRecord(entity, account, report.record, outcomeCode)
    }

    private suspend fun afterRecord(entity: OutboxEntity, account: AccountEntity, record: SendRecord, outcomeCode: String?) {
        val sent = record.state in setOf(SendState.ACCEPTED, SendState.SAVED, SendState.SAVE_FAILED)
        if (sent) {
            accounts.setVerification(entity.identityId, SendVerification.VERIFIED)
            accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.OK, send = StageState.OK) }
        }
        if (record.state == SendState.SAVED) accounts.updateStages(account.id) { it.copy(sentCopy = StageState.OK) }
        if (record.state == SendState.SAVE_FAILED && record.failure == FailureCodes.SAVE_NOT_BY_SERVER) {
            accounts.updateStages(account.id) { it.copy(sentCopy = StageState.FAILED) }
        }
        val failure = outcomeCode ?: record.failure
        if (record.state == SendState.FAILED && failure != null) {
            when (failure.substringBefore('|')) {
                RejectKind.SENDER.name -> accounts.setVerification(entity.identityId, SendVerification.FAILED)
                RejectKind.AUTH.name -> {
                    accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.FAILED) }
                    accounts.markNeedsReauth(account.id)
                }
                RejectKind.TLS.name -> accounts.updateStages(account.id) { it.copy(smtpAuth = StageState.FAILED) }
            }
        }
    }

    private suspend fun transition(id: Long, event: SendEvent, then: suspend (OutboxEntity) -> Unit) {
        val entity = db.outbox().get(id) ?: return
        store.save(id, SendMachine.next(SendRecord(entity.state, entity.attempts, entity.failure), event))
        then(entity)
    }

    private suspend fun withItem(id: Long, block: suspend (OutboxEntity, AccountEntity, SendRoute) -> Unit) {
        val entity = db.outbox().get(id) ?: return
        val account = db.accounts().get(entity.accountId) ?: return
        val route = route(account) ?: return
        lock(account.id).withLock { withContext(Dispatchers.IO) { block(entity, account, route) } }
    }

    private suspend fun route(account: AccountEntity): SendRoute? {
        val secret = credentials.get(account.id) ?: return null
        val profile = Providers.byId(account.providerId)
        val sent = db.folders().byRole(account.id, FolderRole.SENT)
        return SendRoute(
            smtp = ServerEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            imap = ServerEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            credentials = Credentials(account.username, secret),
            sentFolderPath = sent?.path,
            serverSavesSentCopy = profile.serverSavesSentCopy,
        )
    }

    private fun toItem(entity: OutboxEntity) = OutboxItem(
        id = entity.id,
        record = SendRecord(entity.state, entity.attempts, entity.failure),
        message = ComposedMessage(entity.raw, entity.rawWithBcc, entity.envelopeFrom, entity.recipients.split("\n").filter { it.isNotEmpty() }),
        messageId = entity.messageId,
    )

    private fun lock(accountId: Long) = locks.getOrPut(accountId) { Mutex() }
}
