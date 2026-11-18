package app.dustbird.mail.ui.compose

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.send.FailureCodes
import app.dustbird.mail.core.send.SendState
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.sync.EnqueueResult
import java.io.File
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ComposeViewModelTest {
    private val fixture = ComposeFixture()
    private lateinit var first: SeededIdentity
    private lateinit var second: SeededIdentity

    @Before
    fun setUp() {
        fixture.open()
        first = fixture.seedAccount("one", signature = "First signature")
        second = fixture.seedAccount("two", signature = "Second signature")
        fixture.setDefaultIdentity(first.identityId)
    }

    @After
    fun tearDown() = fixture.close()

    private fun drafts() = runBlocking { fixture.db.drafts().observeAll().first() }

    @Test
    fun newMailUsesTheAppDefaultIdentityAndItsSignature() {
        fixture.setDefaultIdentity(second.identityId)
        val viewModel = fixture.openViewModel()
        assertEquals(second.identityId, viewModel.identityId)
        assertEquals(SignatureBlock.append("", "Second signature"), viewModel.body.text.toString())
    }

    @Test
    fun aStaleDefaultIdentityFallsBackToTheFirstOne() {
        fixture.setDefaultIdentity(9999)
        assertEquals(first.identityId, fixture.openViewModel().identityId)
    }

    @Test
    fun leavingAnUntouchedMailStoresNothing() {
        val viewModel = fixture.openViewModel()
        var kept = true
        onMain { kept = viewModel.leave() }
        assertFalse(kept)
        assertTrue(drafts().isEmpty())
    }

    @Test
    fun typedAddressesBecomeChipsAndInvalidOnesStayVisible() {
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.to.input.setTextAndPlaceCursorAtEnd("bad, user@example.com other@example.com,user@example.com ")
            viewModel.to.commit(flush = false)
        }
        assertEquals(listOf("bad", "user@example.com", "other@example.com"), viewModel.to.chips.map { it.address })
        assertEquals(listOf(false, true, true), viewModel.to.chips.map { RecipientInput.isValid(it) })
        assertEquals(RecipientProblem.INVALID, RecipientCheck.of(viewModel.to.chips))
    }

    @Test
    fun knownSendersAreSuggestedButOwnAndAlreadyAddedAddressesAreNot() {
        fixture.seedMessage(first, "friend@example.com", "Hello", "Body", "<known-1@example.com>")
        val viewModel = fixture.openViewModel()
        onMain { viewModel.to.input.setTextAndPlaceCursorAtEnd("fri") }
        waitUntil { viewModel.suggestions(viewModel.to).isNotEmpty() }
        assertEquals(listOf("friend@example.com"), viewModel.suggestions(viewModel.to).map { it.address })
        onMain { viewModel.cc.input.setTextAndPlaceCursorAtEnd("one") }
        assertTrue(viewModel.suggestions(viewModel.cc).isEmpty())
        onMain { viewModel.pick(viewModel.to, viewModel.suggestions(viewModel.to).single()) }
        assertEquals(listOf("friend@example.com"), viewModel.to.chips.map { it.address })
        assertEquals("", viewModel.to.input.text.toString())
        onMain { viewModel.cc.input.setTextAndPlaceCursorAtEnd("fri") }
        assertTrue(viewModel.suggestions(viewModel.cc).isEmpty())
    }

    @Test
    fun changingTheIdentityMovesTheDraftToTheOtherAccountAndSwapsTheSignature() {
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.subject.setTextAndPlaceCursorAtEnd("Hello")
            viewModel.selectIdentity(second.identityId)
        }
        val id = assertNotNull(runBlocking { viewModel.saveLocal() })
        val draft = assertNotNull(runBlocking { fixture.db.drafts().get(id) })
        assertEquals(second.accountId, draft.accountId)
        assertEquals(second.identityId, draft.identityId)
        assertContains(draft.body, "Second signature")
        assertFalse(draft.body.contains("First signature"))
        assertEquals(R.string.compose_account_changed, viewModel.events.tryReceive().getOrNull()?.res)
        onMain { viewModel.selectIdentity(first.identityId) }
        runBlocking { viewModel.saveLocal() }
        assertEquals(first.accountId, runBlocking { fixture.db.drafts().get(id) }?.accountId)
        assertEquals(1, drafts().size)
    }

    @Test
    fun sendWithoutRecipientsIsBlockedBeforeAnythingIsQueued() {
        val viewModel = fixture.openViewModel()
        onMain { viewModel.send() }
        assertEquals(RecipientProblem.EMPTY, viewModel.recipientProblem)
        assertEquals(SendPhase.Idle, viewModel.phase)
        assertNull(viewModel.dialog)
        assertTrue(runBlocking { fixture.db.outbox().observeAll().first() }.isEmpty())
        assertTrue(drafts().isEmpty())
    }

    @Test
    fun sendWithAnInvalidRecipientIsBlocked() {
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.to.add(listOf(EmailAddress("user@example.com"), EmailAddress("broken")))
            viewModel.send()
        }
        assertEquals(RecipientProblem.INVALID, viewModel.recipientProblem)
        assertEquals(SendPhase.Idle, viewModel.phase)
        assertTrue(runBlocking { fixture.db.outbox().observeAll().first() }.isEmpty())
    }

    @Test
    fun sendWaitsForTheDelayKeepsTheDraftAndCanBeCancelled() {
        runBlocking { fixture.container.settings.setSendDelaySeconds(30) }
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.to.add(listOf(EmailAddress("user@example.com")))
            viewModel.subject.setTextAndPlaceCursorAtEnd("Held mail")
            viewModel.sendNow()
        }
        waitUntil { viewModel.exit != null }
        assertEquals(ComposeExit.Held, viewModel.exit)
        val send = fixture.container.send
        val row = runBlocking { fixture.db.outbox().observeAll().first() }.single()
        assertEquals(SendState.QUEUED, row.state)
        assertTrue(row.sendAfter > System.currentTimeMillis() + 20_000)
        assertEquals(row.id, send.tracked.value)
        val draftId = assertNotNull(row.draftId)
        assertNotNull(runBlocking { fixture.db.drafts().get(draftId) })
        assertTrue(drafts().isEmpty())

        runBlocking { send.processAccount(row.accountId) }
        assertEquals(SendState.QUEUED, runBlocking { fixture.db.outbox().get(row.id) }?.state)
        assertFalse(runBlocking { send.hasQueued() })

        assertEquals(draftId, runBlocking { send.cancelHeld(row.id) })
        assertTrue(runBlocking { fixture.db.outbox().observeAll().first() }.isEmpty())
        assertEquals(listOf(draftId), drafts().map { it.id })
        assertNull(send.tracked.value)
        assertNull(runBlocking { send.cancelHeld(row.id) })
    }

    @Test
    fun scheduledMailWaitsInTheOutboxAndIsNotTracked() {
        runBlocking { fixture.container.settings.setSendDelaySeconds(5) }
        val viewModel = fixture.openViewModel()
        val at = System.currentTimeMillis() + 3_600_000
        onMain {
            viewModel.to.add(listOf(EmailAddress("user@example.com")))
            viewModel.subject.setTextAndPlaceCursorAtEnd("Later mail")
            viewModel.schedule(at)
        }
        waitUntil { viewModel.exit != null }
        assertEquals(ComposeExit.Scheduled(at), viewModel.exit)
        val send = fixture.container.send
        val row = runBlocking { fixture.db.outbox().observeAll().first() }.single()
        assertEquals(at, row.sendAfter)
        assertNotNull(row.draftId)
        assertNull(send.tracked.value)
        assertEquals(at, runBlocking { send.nextDue() })
        runBlocking { send.processAccount(row.accountId) }
        assertEquals(SendState.QUEUED, runBlocking { fixture.db.outbox().get(row.id) }?.state)
    }

    @Test
    fun attachedFilesSurviveLaterSavesAndTheSaveBeforeSending() {
        runBlocking { fixture.container.settings.setSendDelaySeconds(30) }
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.to.add(listOf(EmailAddress("user@example.com")))
            viewModel.subject.setTextAndPlaceCursorAtEnd("With a file")
        }
        val id = assertNotNull(runBlocking { viewModel.saveLocal() })
        val source = File(fixture.context.cacheDir, "attach-source.txt").apply { writeText("attached text") }
        assertTrue(runBlocking { fixture.container.drafts.addAttachment(id, source, "메모.txt", "text/plain") })
        fun names() = runBlocking { fixture.db.draftAttachments().forDraft(id) }.map { it.fileName }

        onMain { viewModel.subject.setTextAndPlaceCursorAtEnd("With a file, edited") }
        assertEquals(id, runBlocking { viewModel.saveLocal() })
        assertEquals(listOf("메모.txt"), names())

        onMain { viewModel.send() }
        waitUntil { viewModel.exit != null }
        assertEquals(listOf("메모.txt"), names())
        assertEquals("With a file, edited", runBlocking { fixture.db.drafts().get(id) }?.subject)
    }

    @Test
    fun aDraftThatCannotBeComposedFailsVisiblyInsteadOfBlockingTheQueue() {
        val send = fixture.container.send
        val draftId = runBlocking {
            fixture.container.drafts.save(
                DraftContent(null, first.identityId, listOf(EmailAddress("user@example.com")), emptyList(), emptyList(), "Broken", "body", "<bad id>", emptyList()),
            )
        }
        assertEquals(EnqueueResult.Unsendable, runBlocking { send.enqueue(draftId) })
        assertTrue(runBlocking { fixture.db.outbox().observeAll().first() }.isEmpty())

        val held = runBlocking { send.enqueue(draftId, System.currentTimeMillis() + 600_000) } as EnqueueResult.Queued
        runBlocking {
            fixture.db.outbox().setSendAfter(held.outboxId, 0, System.currentTimeMillis())
            send.processAccount(first.accountId)
        }
        val row = assertNotNull(runBlocking { fixture.db.outbox().get(held.outboxId) })
        assertEquals(SendState.FAILED, row.state)
        assertEquals(FailureCodes.COMPOSE, row.failure)
        assertFalse(runBlocking { send.hasQueued() })
        assertEquals(draftId, runBlocking { send.cancelHeld(held.outboxId) })
        assertEquals(listOf(draftId), drafts().map { it.id })
    }

    @Test
    fun theDraftSurvivesProcessDeathThroughTheSavedStateHandle() {
        val handle = SavedStateHandle()
        val before = fixture.openViewModel(handle = handle)
        onMain {
            before.subject.setTextAndPlaceCursorAtEnd("Kept subject")
            before.to.add(listOf(EmailAddress("user@example.com")))
        }
        val id = assertNotNull(runBlocking { before.saveLocal() })
        assertEquals(id, handle.get<Long>("draftId"))
        val after = fixture.openViewModel(handle = handle)
        assertEquals("Kept subject", after.subject.text.toString())
        assertEquals(listOf("user@example.com"), after.to.chips.map { it.address })
        assertEquals(1, drafts().size)
    }

    @Test
    fun autosaveStoresTheDraftAfterTypingStops() {
        val viewModel = fixture.openViewModel()
        onMain { viewModel.subject.setTextAndPlaceCursorAtEnd("Typed subject") }
        waitUntil(6000) { drafts().isNotEmpty() }
        assertEquals("Typed subject", drafts().single().subject)
    }

    @Test
    fun explicitSaveExplainsThatTheDraftsFolderIsNotMappedYet() {
        val viewModel = fixture.openViewModel()
        onMain {
            viewModel.subject.setTextAndPlaceCursorAtEnd("Saved subject")
            viewModel.saveDraft()
        }
        val message = runBlocking { withTimeout(5000) { viewModel.events.receive() } }
        assertEquals(R.string.compose_saved_no_folder, message.res)
        assertEquals("Saved subject", drafts().single().subject)
    }

    @Test
    fun discardRemovesTheSavedDraft() {
        val viewModel = fixture.openViewModel()
        onMain { viewModel.subject.setTextAndPlaceCursorAtEnd("Gone soon") }
        runBlocking { viewModel.saveLocal() }
        assertEquals(1, drafts().size)
        onMain { viewModel.discard() }
        waitUntil { drafts().isEmpty() }
    }

    @Test
    fun replyCarriesRecipientSubjectThreadHeadersAndQuoteAndAsksToConfirmTheSender() {
        val message = fixture.seedMessage(first, "sender@example.com", "Question", "Original text", "<m1@example.com>")
        val viewModel = fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY, sourceMessageId = message.messageId))
        assertEquals(listOf("sender@example.com"), viewModel.to.chips.map { it.address })
        assertEquals("Re: Question", viewModel.subject.text.toString())
        assertEquals("<m1@example.com>", viewModel.content().inReplyTo)
        assertContains(viewModel.body.text.toString(), "> Original text")
        assertTrue(viewModel.body.text.toString().endsWith(SignatureBlock.block("First signature")))
        assertFalse(viewModel.senderConfirmed)
        assertEquals(first.identityId, viewModel.identityId)
    }

    @Test
    fun forwardStartsWithoutRecipientsAndKeepsTheOriginalText() {
        val message = fixture.seedMessage(second, "sender@example.com", "Question", "Original text", "<m2@example.com>")
        val viewModel = fixture.openViewModel(ComposeArgs(mode = ComposeMode.FORWARD, sourceMessageId = message.messageId))
        assertTrue(viewModel.to.chips.isEmpty())
        assertEquals("Fwd: Question", viewModel.subject.text.toString())
        assertContains(viewModel.body.text.toString(), "Original text")
        assertEquals(second.identityId, viewModel.identityId)
    }
}
