package app.dustbird.mail.ui.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.ui.nav.ComposeArgs
import app.dustbird.mail.ui.nav.ComposeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenAddressReplyTest {
    private val fixture = ComposeFixture()

    @After
    fun tearDown() = fixture.close()

    private fun identities() = runBlocking { fixture.container.accounts.identities.first() }

    @Test
    fun aReplyToAHideMyEmailAddressComesFromThatAddressWithoutTheRealNameOrSignature() {
        fixture.open()
        val owner = fixture.seedAccount("cloud", signature = "Real Name, phone 010", providerId = "icloud", address = "me@mydomain.example", username = "primary@icloud.com")
        fixture.setDefaultIdentity(owner.identityId)
        val message = fixture.seedMessage(
            owner, "shop@store.example", "Order", "Thanks for the order", "<hme@store.example>",
            to = listOf(EmailAddress("quiet.fox42@icloud.com")), cc = listOf(EmailAddress("friend@example.test")),
        )
        val label = fixture.context.getString(R.string.compose_hidden_address_label)

        runBlocking { fixture.container.mail.replySeed(message.messageId, replyAll = false, attribution = "") }
        assertEquals(1, identities().size)

        val viewModel = fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY_ALL, sourceMessageId = message.messageId))
        val hidden = identities().single { it.address == "quiet.fox42@icloud.com" }
        assertEquals(label, hidden.label)
        assertEquals("", hidden.displayName)
        assertEquals("", hidden.signature)
        assertFalse(hidden.isAccountDefault)
        assertEquals(hidden.id, viewModel.identityId)
        assertFalse(viewModel.body.text.contains("Real Name"))
        assertEquals(listOf("shop@store.example"), viewModel.to.chips.map { it.address })
        assertEquals(listOf("friend@example.test"), viewModel.cc.chips.map { it.address })

        val row = runBlocking { fixture.container.mail.messages(MailboxScope.Unified, 10).first().single() }
        assertEquals(RecipientMatch.Matched(listOf(hidden.id)), row.match)

        val otherCloud = fixture.seedAccount("cloud2", providerId = "icloud", address = "me2@mydomain.example", username = "second@icloud.com")
        val second = fixture.seedMessage(otherCloud, "news@paper.example", "News", "Hello", "<hme2@paper.example>", to = listOf(EmailAddress("calm.owl7@icloud.com")))
        fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY, sourceMessageId = second.messageId))
        assertTrue(identities().any { it.address == "calm.owl7@icloud.com" && it.label == "$label 2" })
    }

    @Test
    fun theRealICloudAddressAndOtherServicesKeepTheirUsualSender() {
        fixture.open()
        val owner = fixture.seedAccount("cloud", providerId = "icloud", address = "me@mydomain.example", username = "primary@icloud.com")
        val toPrimary = fixture.seedMessage(owner, "a@example.test", "To primary", "x", "<p@example.test>", to = listOf(EmailAddress("primary@icloud.com")))
        assertEquals(owner.identityId, fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY, sourceMessageId = toPrimary.messageId)).identityId)

        val other = fixture.seedAccount("work")
        val toCloud = fixture.seedMessage(other, "b@example.test", "Other", "x", "<o@example.test>", to = listOf(EmailAddress("someone@icloud.com")))
        assertEquals(other.identityId, fixture.openViewModel(ComposeArgs(mode = ComposeMode.REPLY, sourceMessageId = toCloud.messageId)).identityId)
        assertEquals(2, identities().size)
    }
}
