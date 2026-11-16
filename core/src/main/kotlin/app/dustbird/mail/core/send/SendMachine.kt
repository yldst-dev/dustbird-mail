package app.dustbird.mail.core.send

import java.util.UUID

enum class SendState { DRAFT, QUEUED, SENDING, ACCEPTED, SAVED, FAILED, UNKNOWN, SAVE_FAILED }

sealed interface SendEvent {
    data object Enqueue : SendEvent
    data object Cancel : SendEvent
    data object Begin : SendEvent
    data object SmtpAccepted : SendEvent
    data class SmtpRejected(val reason: String) : SendEvent
    data object ConnectionLostDuringSend : SendEvent
    data class TransientBeforeSend(val reason: String) : SendEvent
    data object RecoverAfterCrash : SendEvent
    data object SentCopyStored : SendEvent
    data class SentCopyFailed(val reason: String) : SendEvent
    data object RetrySave : SendEvent
    data object FoundInSent : SendEvent
    data object UserRetry : SendEvent
    data object UserConfirmSent : SendEvent
    data object UserResend : SendEvent
}

data class SendRecord(val state: SendState, val attempts: Int = 0, val failure: String? = null)

class IllegalSendTransition(val from: SendState, val event: SendEvent) :
    IllegalStateException("Event ${event::class.simpleName} is not allowed in state $from")

object SendMachine {
    const val MAX_TRANSIENT_ATTEMPTS = 3

    fun next(record: SendRecord, event: SendEvent): SendRecord {
        val state = record.state
        return when {
            state == SendState.DRAFT && event == SendEvent.Enqueue -> SendRecord(SendState.QUEUED)
            state == SendState.QUEUED && event == SendEvent.Cancel -> SendRecord(SendState.DRAFT)
            state == SendState.QUEUED && event == SendEvent.Begin -> record.copy(state = SendState.SENDING, attempts = record.attempts + 1, failure = null)
            state == SendState.SENDING && event == SendEvent.SmtpAccepted -> record.copy(state = SendState.ACCEPTED)
            state == SendState.SENDING && event is SendEvent.SmtpRejected -> record.copy(state = SendState.FAILED, failure = event.reason)
            state == SendState.SENDING && event == SendEvent.ConnectionLostDuringSend -> record.copy(state = SendState.UNKNOWN)
            state == SendState.SENDING && event == SendEvent.RecoverAfterCrash -> record.copy(state = SendState.UNKNOWN)
            state == SendState.SENDING && event is SendEvent.TransientBeforeSend ->
                if (record.attempts < MAX_TRANSIENT_ATTEMPTS) record.copy(state = SendState.QUEUED, failure = event.reason)
                else record.copy(state = SendState.FAILED, failure = event.reason)
            state == SendState.ACCEPTED && event == SendEvent.SentCopyStored -> record.copy(state = SendState.SAVED)
            state == SendState.ACCEPTED && event is SendEvent.SentCopyFailed -> record.copy(state = SendState.SAVE_FAILED, failure = event.reason)
            state == SendState.SAVE_FAILED && event == SendEvent.RetrySave -> record.copy(state = SendState.ACCEPTED, failure = null)
            state in foundInSentStates && event == SendEvent.FoundInSent -> record.copy(state = SendState.SAVED, failure = null)
            state == SendState.FAILED && event == SendEvent.UserRetry -> SendRecord(SendState.QUEUED)
            state == SendState.UNKNOWN && event == SendEvent.UserConfirmSent -> record.copy(state = SendState.ACCEPTED)
            state == SendState.UNKNOWN && event == SendEvent.UserResend -> SendRecord(SendState.QUEUED)
            else -> throw IllegalSendTransition(state, event)
        }
    }

    private val foundInSentStates = setOf(SendState.ACCEPTED, SendState.UNKNOWN, SendState.SAVE_FAILED)
}

object MessageIds {
    fun generate(senderAddress: String): String {
        val domain = senderAddress.substringAfter('@', "localhost.localdomain")
        return "<${UUID.randomUUID()}@$domain>"
    }
}
