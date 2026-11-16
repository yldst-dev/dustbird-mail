package app.dustbird.mail.core.send

import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.ParsedContent
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.FetchedMessage
import app.dustbird.mail.core.net.FolderStatus
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.ImapSession
import app.dustbird.mail.core.net.MessageFlags
import app.dustbird.mail.core.net.RejectKind
import app.dustbird.mail.core.net.SmtpOutcome
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.core.testing.TestMail
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeImap : ImapConnector {
    val sentIds = mutableSetOf<String>()
    val appended = mutableListOf<ByteArray>()
    var failConnect = false
    var failAppend = false
    var appearAfterChecks = 0
    var connects = 0
    private var lookups = 0

    override fun connect(endpoint: ServerEndpoint, credentials: Credentials): ImapSession {
        connects++
        if (failConnect) error("imap down")
        return object : ImapSession {
            override fun capabilities() = emptySet<String>()
            override fun listFolders(): List<FolderInfo> = emptyList()
            override fun open(path: String, readOnly: Boolean) = FolderStatus(1, 1, 0)
            override fun listUids(path: String, fromUid: Long, toUid: Long?): List<Long> = emptyList()
            override fun fetchMessages(path: String, uids: List<Long>, deliveryHeaders: List<String>): List<FetchedMessage> = emptyList()
            override fun fetchFlags(path: String, uids: List<Long>): Map<Long, MessageFlags> = emptyMap()
            override fun fetchContent(path: String, uid: Long): ParsedContent? = null
            override fun setSeen(path: String, uid: Long, seen: Boolean) = Unit
            override fun append(path: String, raw: ByteArray, seen: Boolean, draft: Boolean): Long? {
                if (failAppend) error("append failed")
                appended += raw
                sentIds += "<appended>"
                return 1
            }
            override fun findByMessageId(path: String, messageId: String): List<Long> {
                lookups++
                if (appearAfterChecks in 1..lookups) sentIds += messageId
                return if (messageId in sentIds || "<appended>" in sentIds && appended.isNotEmpty()) listOf(1) else emptyList()
            }
            override fun removeByMessageId(path: String, messageId: String): Int = 0
            override fun idle(path: String, onChange: () -> Unit) = Unit
            override fun close() = Unit
        }
    }
}

private class ScriptedSmtp(private val outcomes: MutableList<SmtpOutcome>) : SmtpSender {
    var calls = 0
    override fun send(endpoint: ServerEndpoint, credentials: Credentials, message: ComposedMessage): SmtpOutcome {
        calls++
        return outcomes.removeAt(0)
    }
}

class SendOrchestratorTest {
    private val endpoint = ServerEndpoint("h.example.test", 1, TransportSecurity.TLS)
    private val credentials = Credentials("u", "p")
    private val message = TestMail.compose("<out-1@example.test>")
    private val saved = mutableListOf<SendState>()
    private val store = OutboxStore { _, record -> saved += record.state }

    private fun route(sentPath: String? = "Sent", serverSaves: Boolean = false) = SendRoute(endpoint, endpoint, credentials, sentPath, serverSaves)
    private fun item(record: SendRecord = SendRecord(SendState.QUEUED)) = OutboxItem(1, record, message, "<out-1@example.test>")
    private fun orchestrator(smtp: SmtpSender, imap: ImapConnector) = SendOrchestrator(smtp, imap, store, pause = {}, serverSaveChecks = 3)

    @Test
    fun clientSavedServicesAppendTheCopyWithBccAndReachSaved() {
        val imap = FakeImap()
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted)), imap).send(item(), route())
        assertEquals(SendState.SAVED, report.record.state)
        assertEquals(listOf(SendState.SENDING, SendState.ACCEPTED, SendState.SAVED), saved)
        assertEquals(1, imap.appended.size)
        assertContentEquals(message.rawWithBcc, imap.appended.single())
    }

    @Test
    fun anExistingCopyIsNeverAppendedAgain() {
        val imap = FakeImap().apply { sentIds += "<out-1@example.test>" }
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted)), imap).send(item(), route())
        assertEquals(SendState.SAVED, report.record.state)
        assertTrue(imap.appended.isEmpty())
    }

    @Test
    fun serverSavedServicesAreNotAppendedToWhenTheCopyAppears() {
        val imap = FakeImap().apply { appearAfterChecks = 2 }
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted)), imap).send(item(), route(serverSaves = true))
        assertEquals(SendState.SAVED, report.record.state)
        assertTrue(imap.appended.isEmpty())
    }

    @Test
    fun serverSavedServicesThatDoNotSaveAreReportedAndCanBeFixedByAppending() {
        val imap = FakeImap()
        val smtp = ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted))
        val orchestrator = orchestrator(smtp, imap)
        val report = orchestrator.send(item(), route(serverSaves = true))
        assertEquals(SendState.SAVE_FAILED, report.record.state)
        assertEquals(FailureCodes.SAVE_NOT_BY_SERVER, report.record.failure)
        assertTrue(imap.appended.isEmpty())
        val retried = orchestrator.retrySave(item(report.record), route(serverSaves = true))
        assertEquals(SendState.SAVED, retried.state)
        assertEquals(1, imap.appended.size)
        assertEquals(1, smtp.calls)
    }

    @Test
    fun saveFailureIsNotASendFailureAndNeverResendsTheMessage() {
        val imap = FakeImap().apply { failAppend = true }
        val smtp = ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted))
        val orchestrator = orchestrator(smtp, imap)
        val report = orchestrator.send(item(), route())
        assertEquals(SendState.SAVE_FAILED, report.record.state)
        assertEquals(FailureCodes.SAVE_ERROR, report.record.failure)
        imap.failAppend = false
        assertEquals(SendState.SAVED, orchestrator.retrySave(item(report.record), route()).state)
        assertEquals(1, smtp.calls)
    }

    @Test
    fun imapOutageAfterAcceptanceOnlyAffectsTheSavedCopy() {
        val imap = FakeImap().apply { failConnect = true }
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted)), imap).send(item(), route())
        assertEquals(SendState.SAVE_FAILED, report.record.state)
    }

    @Test
    fun missingSentFolderMappingIsASaveProblem() {
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Accepted)), FakeImap()).send(item(), route(sentPath = null))
        assertEquals(SendState.SAVE_FAILED, report.record.state)
        assertEquals(FailureCodes.SAVE_NO_FOLDER, report.record.failure)
    }

    @Test
    fun explicitRejectionFailsWithTheServerCodeAndTouchesNoFolder() {
        val imap = FakeImap()
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Rejected(RejectKind.SENDER, 553, "no"))), imap).send(item(), route())
        assertEquals(SendState.FAILED, report.record.state)
        assertEquals("SENDER|553", report.record.failure)
        assertEquals(0, imap.connects)
    }

    @Test
    fun networkProblemsBeforeSendingRetryAndThenFail() {
        val smtp = ScriptedSmtp(MutableList(3) { SmtpOutcome.NotSent("down") })
        val orchestrator = orchestrator(smtp, FakeImap())
        var record = SendRecord(SendState.QUEUED)
        val states = mutableListOf<SendState>()
        while (record.state == SendState.QUEUED) {
            record = orchestrator.send(item(record), route()).record
            states += record.state
        }
        assertEquals(listOf(SendState.QUEUED, SendState.QUEUED, SendState.FAILED), states)
        assertEquals(FailureCodes.NETWORK, record.failure)
        assertEquals(3, smtp.calls)
    }

    @Test
    fun unknownOutcomeIsNotResentAndIsResolvedFromTheSentFolder() {
        val imap = FakeImap()
        val smtp = ScriptedSmtp(mutableListOf(SmtpOutcome.Unknown("dropped")))
        val orchestrator = orchestrator(smtp, imap)
        val first = orchestrator.send(item(), route())
        assertEquals(SendState.UNKNOWN, first.record.state)
        assertEquals(1, smtp.calls)
        val stillUnknown = orchestrator.resolveUnknown(item(first.record), route())
        assertEquals(SendState.UNKNOWN, stillUnknown.state)
        imap.sentIds += "<out-1@example.test>"
        assertEquals(SendState.SAVED, orchestrator.resolveUnknown(item(first.record), route()).state)
        assertEquals(1, smtp.calls)
    }

    @Test
    fun unknownOutcomeThatTheServerActuallyKeptIsFoundImmediately() {
        val imap = FakeImap().apply { sentIds += "<out-1@example.test>" }
        val report = orchestrator(ScriptedSmtp(mutableListOf(SmtpOutcome.Unknown("dropped"))), imap).send(item(), route())
        assertEquals(SendState.SAVED, report.record.state)
        assertEquals(listOf(SendState.SENDING, SendState.UNKNOWN, SendState.SAVED), saved)
    }

    @Test
    fun confirmingAnUnknownSendSavesTheCopyWithoutSendingAgain() {
        val imap = FakeImap()
        val smtp = ScriptedSmtp(mutableListOf())
        val record = orchestrator(smtp, imap).saveAfterConfirmation(item(SendRecord(SendState.UNKNOWN, 1)), route())
        assertEquals(SendState.SAVED, record.state)
        assertEquals(1, imap.appended.size)
        assertEquals(0, smtp.calls)
    }
}
