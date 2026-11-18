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
    fun ownAddressesOfAnICloudMailboxAreShownInsteadOfUnknown() {
        val custom = identity(5, 30, "me@mydomain.example", "me", isDefault = true)
        val own = RecipientMatcher.ownAddresses("icloud", "primary@icloud.com", listOf(custom))
        fun to(vararg values: String) = ReceivedAddressing(values.map { addr(it) }, emptyList(), emptyList())
        assertEquals(listOf(5L), assertIs<RecipientMatch.Matched>(RecipientMatcher.match(to("Me@MyDomain.example"), listOf(custom), own)).identityIds)
        assertEquals(RecipientMatch.OwnAddress("shop@mydomain.example"), RecipientMatcher.match(to("shop@mydomain.example"), listOf(custom), own))
        assertEquals(RecipientMatch.OwnAddress("primary@icloud.com"), RecipientMatcher.match(to("Primary@iCloud.com"), listOf(custom), own))
        assertEquals(RecipientMatch.OwnAddress("random.relay@icloud.com"), RecipientMatcher.match(to("random.relay@icloud.com"), listOf(custom), own))
        assertEquals(RecipientMatch.OwnAddress("abc@privaterelay.appleid.com"), RecipientMatcher.match(to("abc@privaterelay.appleid.com"), listOf(custom), own))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(to("list@lists.example"), listOf(custom), own))
        val delivered = ReceivedAddressing(listOf(addr("list@lists.example")), emptyList(), listOf("shop@mydomain.example"))
        assertEquals(RecipientMatch.OwnAddress("shop@mydomain.example"), RecipientMatcher.match(delivered, listOf(custom), own))
    }

    @Test
    fun hideMyEmailIsAnAppleMailAddressOtherThanTheLoginOnICloud() {
        assertTrue(RecipientMatcher.isHideMyEmail("icloud", "primary@icloud.com", "Quiet.Fox42@iCloud.com"))
        assertTrue(RecipientMatcher.isHideMyEmail("icloud", "primary@icloud.com", "old@me.com"))
        assertFalse(RecipientMatcher.isHideMyEmail("icloud", "primary@icloud.com", "Primary@iCloud.com"))
        assertFalse(RecipientMatcher.isHideMyEmail("icloud", "primary@icloud.com", "abc@privaterelay.appleid.com"))
        assertFalse(RecipientMatcher.isHideMyEmail("icloud", "primary@icloud.com", "shop@mydomain.example"))
        assertFalse(RecipientMatcher.isHideMyEmail("gmail", "me@gmail.com", "someone@icloud.com"))
    }

    @Test
    fun onlyTheLoginAddressCountsOutsideICloud() {
        val work = identity(6, 40, "me@company.example", "work", isDefault = true)
        val own = RecipientMatcher.ownAddresses("custom", "login@company.example", listOf(work))
        fun to(value: String) = ReceivedAddressing(listOf(addr(value)), emptyList(), emptyList())
        assertEquals(RecipientMatch.OwnAddress("login@company.example"), RecipientMatcher.match(to("login@company.example"), listOf(work), own))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(to("colleague@company.example"), listOf(work), own))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(to("someone@icloud.com"), listOf(work), own))
        assertEquals(RecipientMatch.Unknown, RecipientMatcher.match(to("x@company.example"), listOf(work), RecipientMatcher.ownAddresses("gmail", "not-an-address", listOf(work))))
    }

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
        sentByMe: Boolean = false,
    ) = OriginalMessage(10, "<orig@x>", references, from, replyTo, to, cc, subject, "line1\n\nline3", matched, sentByMe)

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
    fun replyingToAMessageFromTheSentFolderTargetsTheOriginalRecipients() {
        val seed = ReplyComposer.reply(
            original(from = addr("me@example.com"), to = listOf(addr("friend@example.net"), addr("biz@example.com")), sentByMe = true),
            all,
            replyAll = false,
            attribution = "a",
        )
        assertEquals(listOf("friend@example.net", "biz@example.com"), seed.to.map { it.address })
    }

    @Test
    fun replyToAMessageFromYourOwnOtherAddressKeepsThatSender() {
        val seed = ReplyComposer.reply(original(from = addr("OTHER@example.org")), all, replyAll = false, attribution = "a")
        assertEquals(listOf("OTHER@example.org"), seed.to.map { it.address })
    }

    @Test
    fun replyAllNeverLeavesTheToListEmpty() {
        val seed = ReplyComposer.reply(
            original(from = addr("other@example.org"), to = listOf(addr("biz@example.com")), cc = listOf(addr("me@example.com"))),
            all,
            replyAll = true,
            attribution = "a",
        )
        assertEquals(listOf("other@example.org", "biz@example.com"), seed.to.map { it.address })
        assertTrue(seed.cc.isEmpty())
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
