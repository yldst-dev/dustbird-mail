package app.dustbird.mail.core.send

import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.net.SmtpOutcome
import app.dustbird.mail.core.net.SmtpSender

data class OutboxItem(val id: Long, val record: SendRecord, val message: ComposedMessage, val messageId: String)

data class SendRoute(
    val smtp: ServerEndpoint,
    val imap: ServerEndpoint,
    val credentials: Credentials,
    val sentFolderPath: String?,
    val serverSavesSentCopy: Boolean,
)

data class SendReport(val record: SendRecord, val outcome: SmtpOutcome?)

fun interface OutboxStore {
    fun save(id: Long, record: SendRecord)
}

object FailureCodes {
    const val NETWORK = "NETWORK"
    const val SAVE_NO_FOLDER = "SAVE_NO_FOLDER"
    const val SAVE_NOT_BY_SERVER = "SAVE_NOT_BY_SERVER"
    const val SAVE_ERROR = "SAVE_ERROR"
    const val COMPOSE = "COMPOSE"

    fun rejected(kind: RejectKind, code: Int?): String = if (code == null) kind.name else "${kind.name}|$code"
}

class SendOrchestrator(
    private val smtp: SmtpSender,
    private val imap: ImapConnector,
    private val store: OutboxStore,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val serverSaveChecks: Int = 3,
    private val serverSaveWaitMillis: Long = 1000,
) {
    fun send(item: OutboxItem, route: SendRoute): SendReport {
        var record = advance(item.id, item.record, SendEvent.Begin)
        val outcome = smtp.send(route.smtp, route.credentials, item.message)
        record = when (outcome) {
            SmtpOutcome.Accepted -> advance(item.id, record, SendEvent.SmtpAccepted)
            is SmtpOutcome.Rejected -> advance(item.id, record, SendEvent.SmtpRejected(FailureCodes.rejected(outcome.kind, outcome.code)))
            is SmtpOutcome.NotSent -> advance(item.id, record, SendEvent.TransientBeforeSend(FailureCodes.NETWORK))
            is SmtpOutcome.Unknown -> advance(item.id, record, SendEvent.ConnectionLostDuringSend)
        }
        record = when (record.state) {
            SendState.ACCEPTED -> storeSentCopy(item, record, route, forceAppend = false)
            SendState.UNKNOWN -> lookForCopy(item, record, route)
            else -> record
        }
        return SendReport(record, outcome)
    }

    fun resolveUnknown(item: OutboxItem, route: SendRoute): SendRecord = lookForCopy(item, item.record, route)

    fun retrySave(item: OutboxItem, route: SendRoute): SendRecord {
        val ready = advance(item.id, item.record, SendEvent.RetrySave)
        return storeSentCopy(item, ready, route, forceAppend = true)
    }

    fun saveAfterConfirmation(item: OutboxItem, route: SendRoute): SendRecord {
        val confirmed = advance(item.id, item.record, SendEvent.UserConfirmSent)
        return storeSentCopy(item, confirmed, route, forceAppend = true)
    }

    private fun lookForCopy(item: OutboxItem, record: SendRecord, route: SendRoute): SendRecord {
        val path = route.sentFolderPath ?: return record
        val found = try {
            imap.connect(route.imap, route.credentials).use { session ->
                session.open(path, readOnly = true)
                session.findByMessageId(path, item.messageId).isNotEmpty()
            }
        } catch (_: Exception) {
            false
        }
        return if (found) advance(item.id, record, SendEvent.FoundInSent) else record
    }

    private fun storeSentCopy(item: OutboxItem, record: SendRecord, route: SendRoute, forceAppend: Boolean): SendRecord {
        val path = route.sentFolderPath ?: return advance(item.id, record, SendEvent.SentCopyFailed(FailureCodes.SAVE_NO_FOLDER))
        return try {
            imap.connect(route.imap, route.credentials).use { session ->
                session.open(path)
                when {
                    session.findByMessageId(path, item.messageId).isNotEmpty() -> advance(item.id, record, SendEvent.SentCopyStored)
                    route.serverSavesSentCopy && !forceAppend && waitForServerCopy(session, path, item.messageId) ->
                        advance(item.id, record, SendEvent.SentCopyStored)
                    route.serverSavesSentCopy && !forceAppend ->
                        advance(item.id, record, SendEvent.SentCopyFailed(FailureCodes.SAVE_NOT_BY_SERVER))
                    else -> {
                        session.append(path, item.message.rawWithBcc, seen = true)
                        advance(item.id, record, SendEvent.SentCopyStored)
                    }
                }
            }
        } catch (_: Exception) {
            advance(item.id, record, SendEvent.SentCopyFailed(FailureCodes.SAVE_ERROR))
        }
    }

    private fun waitForServerCopy(session: app.dustbird.mail.core.net.ImapSession, path: String, messageId: String): Boolean {
        repeat(serverSaveChecks) {
            pause(serverSaveWaitMillis)
            session.open(path, readOnly = true)
            if (session.findByMessageId(path, messageId).isNotEmpty()) return true
        }
        return false
    }

    private fun advance(id: Long, record: SendRecord, event: SendEvent): SendRecord {
        val next = SendMachine.next(record, event)
        store.save(id, next)
        return next
    }
}
