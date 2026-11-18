package app.dustbird.mail.ui.compose

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.agent.ScriptedBackend
import app.dustbird.mail.agent.callItem
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.data.AgentMode
import app.dustbird.mail.data.AgentSettings
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeWriterTest {
    private var turn: (AgentRequest) -> List<AgentItem.Model> = { emptyList() }
    private val backend = ScriptedBackend(listOf { request -> turn(request) })
    private val fixture = ComposeFixture(backend)

    private fun connect() = runBlocking {
        fixture.open()
        fixture.container.agent.saveConnection(AgentMode.API_KEY, "", "", "test-key")
        fixture.container.agent.update { it.copy(consent = true) }
    }

    @After
    fun tearDown() {
        runBlocking { fixture.container.settings.updateAgent { AgentSettings() } }
        fixture.close()
    }

    @Test
    fun aWrittenReplyReplacesOnlyTheOwnTextAndKeepsTheQuoteAndSignature() {
        connect()
        val owner = fixture.seedAccount("one", signature = "First signature")
        fixture.setDefaultIdentity(owner.identityId)
        val message = fixture.seedMessage(owner, "sender@example.com", "Question", "Original text", "<m1@example.com>")
        turn = { listOf(callItem("c1", ComposeAssist.TOOL, JSONObject().put("subject", "다른 제목").put("body", "화요일 2시에 뵙겠습니다.\n-- \n가짜 서명"))) }
        val viewModel = fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY, sourceMessageId = message.messageId))
        val before = viewModel.body.text.toString()
        onMain { viewModel.body.setTextAndPlaceCursorAtEnd("먼저 쓴 글$before") }

        onMain { viewModel.write("화요일 2시 가능하다고 답장해 줘") }
        waitUntil { viewModel.writing is Writing.Done }
        val sent = (backend.requests.single().items.single() as AgentItem.User).text
        assertTrue(sent.contains("<request>\n화요일 2시 가능하다고 답장해 줘"))
        assertTrue(sent.contains("<draft>\n먼저 쓴 글\n</draft>"))
        assertTrue(sent.contains("> Original text"))
        assertFalse(sent.contains("First signature"))

        onMain { viewModel.applyWriting() }
        assertEquals("화요일 2시에 뵙겠습니다.\n\n" + before.trim(), viewModel.body.text.toString())
        assertTrue(viewModel.body.text.endsWith(SignatureBlock.block("First signature")))
        assertEquals("Re: Question", viewModel.subject.text.toString())
        assertEquals(Writing.Idle, viewModel.writing)
    }

    @Test
    fun aNewMailTakesTheSuggestedSubjectAndAFailureIsReported() {
        connect()
        val owner = fixture.seedAccount("two")
        fixture.setDefaultIdentity(owner.identityId)
        turn = { listOf(callItem("c1", ComposeAssist.TOOL, JSONObject().put("subject", "회의 제안\n드립니다").put("body", "다음 주 회의를 제안드립니다."))) }
        val viewModel = fixture.openViewModel()
        onMain { viewModel.write("회의 제안 메일 써 줘") }
        waitUntil { viewModel.writing is Writing.Done }
        onMain { viewModel.applyWriting() }
        assertEquals("다음 주 회의를 제안드립니다.", viewModel.body.text.toString())
        assertEquals("회의 제안 드립니다", viewModel.subject.text.toString())

        turn = { throw AgentBackendException(BackendFailure.AUTH, "expired") }
        onMain { viewModel.subject.setTextAndPlaceCursorAtEnd("유지할 제목") }
        onMain { viewModel.write("짧게 줄여 줘") }
        waitUntil { viewModel.writing is Writing.Failed }
        assertEquals(BackendFailure.AUTH, (viewModel.writing as Writing.Failed).failure)
        assertEquals("다음 주 회의를 제안드립니다.", viewModel.body.text.toString())
        assertEquals("유지할 제목", viewModel.subject.text.toString())
    }
}
