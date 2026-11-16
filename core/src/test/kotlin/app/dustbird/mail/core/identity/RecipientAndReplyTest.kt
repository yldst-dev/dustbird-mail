package app.dustbird.mail.core.identity

import app.dustbird.mail.core.identity
import app.dustbird.mail.core.model.EmailAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipientAndReplyTest {
    private val me = identity(1, 10, "me@example.com", "me", isDefault = true)
    private val biz = identity(2, 10, "biz@example.com", "biz")
    private val other = identity(3, 20, "other@example.org", "other", isDefault = true)
    private val all = listOf(me, biz, other)
    private val accountOne = listOf(me, biz)

    private fun addr(value: String, name: String? = null) = EmailAddress(value, name)

    @Test
    fun matchesToAndCcFirst() {
        val result = RecipientMatcher.match(ReceivedAddressing(listOf(addr("BIZ@example.com")), listOf(addr("me@example.com")), emptyList()), accountOne)
        assertEquals(listOf(1L, 2L), assertIs<RecipientMatch.Matched>(result).identityIds)
    }

    @Test
    fun fallsBackToDeliveryHeadersWhenVisibleRecipientsDoNotMatch() {
        val received = ReceivedAddressing(listOf(addr("list@lists.example")), emptyList(), listOf("<biz@example.com>"))
        assertEquals(listOf(2L), assertIs<RecipientMatch.Matched>(RecipientMatcher.match(received, accountOne)).identityIds)
    }

    @Test
    fun reportsUnknownInsteadOfGuessing() {
        val received = ReceivedAddressing(listOf(addr("list@lists.example")), emptyList(), listOf("someone@else.example"))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(received, accountOne))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(ReceivedAddressing(emptyList(), emptyList(), emptyList()), accountOne))
    }

    @Test
    fun otherAccountsIdentitiesDoNotMatch() {
        val received = ReceivedAddressing(listOf(addr("other@example.org")), emptyList(), emptyList())
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(received, accountOne))
    }

    private fun original(
        from: EmailAddress? = addr("sender@example.net", "보낸이"),
        replyTo: List<EmailAddress> = emptyList(),
        to: List<EmailAddress> = listOf(addr("biz@example.com")),
        cc: List<EmailAddress> = emptyList(),
        matched: List<Long> = listOf(2),
        subject: String = "안녕",
        references: List<String> = listOf("<r1@x>"),
    ) = OriginalMessage(10, "<orig@x>", references, from, replyTo, to, cc, subject, "line1\n\nline3", matched)

    @Test
    fun replyGoesToSenderFromTheMatchedIdentityWithThreadHeaders() {
        val seed = ReplyComposer.reply(original(), all, replyAll = false, attribution = "위 사람이 썼습니다:")
        assertEquals(2L, seed.identityId)
        assertFalse(seed.identityNeedsConfirmation)
        assertEquals(listOf("sender@example.net"), seed.to.map { it.address })
        assertTrue(seed.cc.isEmpty())
        assertEquals("Re: 안녕", seed.subject)
        assertEquals("<orig@x>", seed.inReplyTo)
        assertEquals(listOf("<r1@x>", "<orig@x>"), seed.references)
        assertEquals("\n\n위 사람이 썼습니다:\n> line1\n>\n> line3", seed.body)
    }

    @Test
    fun replyPrefixIsNotStackedAndReplyToHeaderWins() {
        val seed = ReplyComposer.reply(original(subject = "RE: 안녕", replyTo = listOf(addr("reply@example.net"))), all, false, "a")
        assertEquals("Re: 안녕", seed.subject)
        assertEquals(listOf("reply@example.net"), seed.to.map { it.address })
        assertEquals("Re: 안녕", ReplyComposer.reply(original(subject = "답장: 안녕"), all, false, "a").subject)
    }

    @Test
    fun unknownRecipientFallsBackToAccountDefaultAndAsksForConfirmation() {
        val seed = ReplyComposer.reply(original(matched = emptyList()), all, false, "a")
        assertEquals(1L, seed.identityId)
        assertTrue(seed.identityNeedsConfirmation)
    }

    @Test
    fun ambiguousMatchAsksForConfirmation() {
        val seed = ReplyComposer.reply(original(matched = listOf(2, 1)), all, false, "a")
        assertEquals(2L, seed.identityId)
        assertTrue(seed.identityNeedsConfirmation)
    }

    @Test
    fun replyAllRemovesEveryOwnIdentityAcrossAccountsAndDuplicates() {
        val seed = ReplyComposer.reply(
            original(
                to = listOf(addr("biz@example.com"), addr("friend@example.net"), addr("OTHER@example.org")),
                cc = listOf(addr("sender@example.net"), addr("me@example.com"), addr("cc@example.net"), addr("CC@example.net")),
            ),
            all,
            replyAll = true,
            attribution = "a",
        )
        assertEquals(listOf("sender@example.net", "friend@example.net"), seed.to.map { it.address })
        assertEquals(listOf("cc@example.net"), seed.cc.map { it.address })
    }

    @Test
    fun replyingToOwnMessageTargetsTheOriginalRecipients() {
        val seed = ReplyComposer.reply(
            original(from = addr("me@example.com"), to = listOf(addr("friend@example.net"), addr("biz@example.com"))),
            all,
            replyAll = false,
            attribution = "a",
        )
        assertEquals(listOf("friend@example.net"), seed.to.map { it.address })
    }

    @Test
    fun forwardKeepsNoRecipientsOrThreadHeaders() {
        val seed = ReplyComposer.forward(original(subject = "Fwd: 안녕"), all, "---- 전달된 메시지 ----")
        assertTrue(seed.to.isEmpty() && seed.cc.isEmpty())
        assertNull(seed.inReplyTo)
        assertTrue(seed.references.isEmpty())
        assertEquals("Fwd: 안녕", seed.subject)
        assertTrue(seed.body.contains("line1"))
    }
}
