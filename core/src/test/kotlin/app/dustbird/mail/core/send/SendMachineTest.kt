package app.dustbird.mail.core.send

import app.dustbird.mail.core.send.SendState.ACCEPTED
import app.dustbird.mail.core.send.SendState.DRAFT
import app.dustbird.mail.core.send.SendState.FAILED
import app.dustbird.mail.core.send.SendState.QUEUED
import app.dustbird.mail.core.send.SendState.SAVED
import app.dustbird.mail.core.send.SendState.SAVE_FAILED
import app.dustbird.mail.core.send.SendState.SENDING
import app.dustbird.mail.core.send.SendState.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendMachineTest {
    private val events: List<SendEvent> = listOf(
        SendEvent.Enqueue, SendEvent.Cancel, SendEvent.Begin, SendEvent.SmtpAccepted, SendEvent.SmtpRejected("r"),
        SendEvent.ConnectionLostDuringSend, SendEvent.TransientBeforeSend("t"), SendEvent.RecoverAfterCrash,
        SendEvent.SentCopyStored, SendEvent.SentCopyFailed("s"), SendEvent.RetrySave, SendEvent.FoundInSent,
        SendEvent.UserRetry, SendEvent.UserConfirmSent, SendEvent.UserResend,
    )

    private fun expected(state: SendState, event: SendEvent): SendState? = when (state) {
        DRAFT -> if (event == SendEvent.Enqueue) QUEUED else null
        QUEUED -> when (event) {
            SendEvent.Cancel -> DRAFT
            SendEvent.Begin -> SENDING
            else -> null
        }
        SENDING -> when (event) {
            SendEvent.SmtpAccepted -> ACCEPTED
            is SendEvent.SmtpRejected -> FAILED
            SendEvent.ConnectionLostDuringSend, SendEvent.RecoverAfterCrash -> UNKNOWN
            is SendEvent.TransientBeforeSend -> QUEUED
            else -> null
        }
        ACCEPTED -> when (event) {
            SendEvent.SentCopyStored, SendEvent.FoundInSent -> SAVED
            is SendEvent.SentCopyFailed -> SAVE_FAILED
            else -> null
        }
        SAVE_FAILED -> when (event) {
            SendEvent.RetrySave -> ACCEPTED
            SendEvent.FoundInSent -> SAVED
            else -> null
        }
        FAILED -> if (event == SendEvent.UserRetry) QUEUED else null
        UNKNOWN -> when (event) {
            SendEvent.UserConfirmSent -> ACCEPTED
            SendEvent.UserResend -> QUEUED
            SendEvent.FoundInSent -> SAVED
            else -> null
        }
        SAVED -> null
    }

    @Test
    fun everyStateAndEventPairBehavesAsSpecified() {
        for (state in SendState.entries) for (event in events) {
            val record = SendRecord(state, attempts = 0)
            val want = expected(state, event)
            if (want == null) {
                assertFailsWith<IllegalSendTransition>("$state + $event") { SendMachine.next(record, event) }
            } else {
                assertEquals(want, SendMachine.next(record, event).state, "$state + $event")
            }
        }
    }

    @Test
    fun savedIsTerminal() {
        events.forEach { event -> assertFailsWith<IllegalSendTransition> { SendMachine.next(SendRecord(SAVED), event) } }
    }

    @Test
    fun happyPathReachesSaved() {
        var record = SendRecord(DRAFT)
        listOf(SendEvent.Enqueue, SendEvent.Begin, SendEvent.SmtpAccepted, SendEvent.SentCopyStored).forEach { record = SendMachine.next(record, it) }
        assertEquals(SAVED, record.state)
        assertEquals(1, record.attempts)
    }

    @Test
    fun transientFailuresRetryUpToTheLimitThenFail() {
        var record = SendRecord(QUEUED)
        repeat(SendMachine.MAX_TRANSIENT_ATTEMPTS - 1) {
            record = SendMachine.next(SendMachine.next(record, SendEvent.Begin), SendEvent.TransientBeforeSend("net"))
            assertEquals(QUEUED, record.state)
        }
        record = SendMachine.next(SendMachine.next(record, SendEvent.Begin), SendEvent.TransientBeforeSend("net"))
        assertEquals(FAILED, record.state)
        assertEquals("net", record.failure)
        assertEquals(SendMachine.MAX_TRANSIENT_ATTEMPTS, record.attempts)
    }

    @Test
    fun userRetryAndResendStartFromAFreshAttemptCount() {
        val failed = SendRecord(FAILED, attempts = 3, failure = "x")
        assertEquals(SendRecord(QUEUED), SendMachine.next(failed, SendEvent.UserRetry))
        assertEquals(SendRecord(QUEUED), SendMachine.next(SendRecord(UNKNOWN, 1), SendEvent.UserResend))
    }

    @Test
    fun unknownOutcomeIsNeverRetriedAutomatically() {
        val unknown = SendMachine.next(SendMachine.next(SendRecord(QUEUED), SendEvent.Begin), SendEvent.ConnectionLostDuringSend)
        assertEquals(UNKNOWN, unknown.state)
        assertFailsWith<IllegalSendTransition> { SendMachine.next(unknown, SendEvent.Begin) }
    }

    @Test
    fun saveFailureIsNotASendFailureAndCanBeRetried() {
        val failed = SendMachine.next(SendRecord(ACCEPTED), SendEvent.SentCopyFailed("disk"))
        assertEquals(SAVE_FAILED, failed.state)
        assertEquals("disk", failed.failure)
        val retried = SendMachine.next(failed, SendEvent.RetrySave)
        assertEquals(ACCEPTED, retried.state)
        assertNull(retried.failure)
    }

    @Test
    fun beginClearsEarlierFailureText() {
        val record = SendMachine.next(SendRecord(QUEUED, 1, "old"), SendEvent.Begin)
        assertNull(record.failure)
        assertEquals(2, record.attempts)
    }

    @Test
    fun generatedMessageIdsUseTheSenderDomainAndAreUnique() {
        val first = MessageIds.generate("me@example.com")
        val second = MessageIds.generate("me@example.com")
        assertTrue(first.startsWith("<") && first.endsWith("@example.com>"))
        assertTrue(first != second)
    }
}
