package app.dustbird.mail.ui.accounts

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.core.identity.AccountRules
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.theme.DustbirdTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class AccountManageMemoryCredentials : CredentialStore {
    private val secrets = mutableMapOf<Long, String>()

    override fun put(accountId: Long, secret: String) {
        secrets[accountId] = secret
    }

    override fun get(accountId: Long): String? = secrets[accountId]

    override fun delete(accountId: Long) {
        secrets.remove(accountId)
    }

    override fun deleteAll() = secrets.clear()
}

internal class AccountManageTestKit(private val compose: ComposeContentTestRule) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val db: MailDatabase = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
    val container = AppContainer(
        context,
        connector = ImapConnector { _, _ -> error("network is not available in tests") },
        smtpSender = SmtpSender { _, _, _ -> error("network is not available in tests") },
        credentials = AccountManageMemoryCredentials(),
        db = db,
    )

    fun close() {
        container.scope.cancel()
        db.close()
    }

    fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalAppContainer provides container) { DustbirdTheme { content() } }
    }

    fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    fun seedAccount(
        key: String,
        name: String,
        label: String,
        sortOrder: Int,
        needsReauth: Boolean = false,
        providerId: String = "custom",
        syncMode: SyncMode = SyncMode.PERIODIC,
    ): Long = runBlocking {
        val imapHost = "imap.$key.example.com"
        val address = "$key@example.com"
        val id = db.accounts().insert(
            AccountEntity(
                providerId = providerId,
                displayName = name,
                label = label,
                username = address,
                imapHost = imapHost,
                imapPort = 993,
                imapSecurity = TransportSecurity.TLS,
                smtpHost = "smtp.$key.example.com",
                smtpPort = 587,
                smtpSecurity = TransportSecurity.STARTTLS,
                authMethod = AuthMethod.APP_PASSWORD,
                sortOrder = sortOrder,
                syncMode = syncMode,
                notificationsEnabled = true,
                needsReauth = needsReauth,
                mailboxKey = AccountRules.mailboxKey(imapHost, address),
            ),
        )
        db.identities().insert(
            IdentityEntity(
                accountId = id,
                address = address,
                addressKey = address,
                displayName = "",
                signature = "",
                label = key,
                labelKey = key,
                sortOrder = 0,
                isAccountDefault = true,
                verification = SendVerification.UNVERIFIED,
            ),
        )
        id
    }
}

@RunWith(AndroidJUnit4::class)
class AccountsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var kit: AccountManageTestKit

    @Before
    fun setUp() {
        kit = AccountManageTestKit(compose)
    }

    @After
    fun tearDown() = kit.close()

    @Test
    fun listsEveryAccountInSortOrder() {
        kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        kit.seedAccount("second", "Home Mail", "Home", sortOrder = 1)
        kit.show { AccountsRoute(onBack = {}, onAdd = {}, onOpenAccount = {}) }
        kit.waitForText("Home Mail")
        val first = compose.onNodeWithText("Work Mail").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText("Home Mail").getUnclippedBoundsInRoot()
        assertTrue(first.top < second.top)
    }

    @Test
    fun needsReauthAccountShowsItsActionAndOpensTheAccount() {
        kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        val second = kit.seedAccount("second", "Home Mail", "Home", sortOrder = 1, needsReauth = true)
        val opened = mutableListOf<Long>()
        kit.show { AccountsRoute(onBack = {}, onAdd = {}, onOpenAccount = { opened += it }) }
        val action = kit.string(R.string.accounts_reauth_action)
        kit.waitForText(action)
        compose.onAllNodesWithText(action).assertCountEquals(1)
        compose.onNodeWithText(action).performClick()
        assertEquals(listOf(second), opened)
    }

    @Test
    fun unknownStagesAreNeverShownAsSuccess() {
        kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        kit.show { AccountsRoute(onBack = {}, onAdd = {}, onOpenAccount = {}) }
        val unknown = kit.string(R.string.accounts_stage_unknown)
        kit.waitForText(unknown)
        compose.onAllNodesWithText(unknown).assertCountEquals(5)
        compose.onAllNodesWithText(kit.string(R.string.accounts_stage_ok)).assertCountEquals(0)
    }

    @Test
    fun moveDownPersistsTheNewOrder() {
        val first = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        val second = kit.seedAccount("second", "Home Mail", "Home", sortOrder = 1)
        kit.show { AccountsRoute(onBack = {}, onAdd = {}, onOpenAccount = {}) }
        kit.waitForText("Home Mail")
        compose.onNodeWithContentDescription(kit.string(R.string.accounts_move_down, "Work Mail")).performClick()
        compose.waitUntil(5_000) { runBlocking { kit.container.accounts.accounts.first().map { it.id } } == listOf(second, first) }
    }

    @Test
    fun singleAccountHidesReorderControls() {
        kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        kit.show { AccountsRoute(onBack = {}, onAdd = {}, onOpenAccount = {}) }
        kit.waitForText("Work Mail")
        compose.onAllNodesWithContentDescription(kit.string(R.string.accounts_move_down, "Work Mail")).assertCountEquals(0)
    }

    @Test
    fun emptyStateOffersTheAddButton() {
        var added = 0
        kit.show { AccountsRoute(onBack = {}, onAdd = { added++ }, onOpenAccount = {}) }
        kit.waitForText(kit.string(R.string.accounts_empty_title))
        compose.onNodeWithText(kit.string(R.string.accounts_add)).performClick()
        assertEquals(1, added)
    }
}
