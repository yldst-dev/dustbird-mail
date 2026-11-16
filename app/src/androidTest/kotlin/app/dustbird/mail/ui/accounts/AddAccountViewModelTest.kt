package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.sync.SyncOutcome
import app.dustbird.mail.ui.accounts.add.AddAccountUiState
import app.dustbird.mail.ui.accounts.add.AddAccountViewModel
import app.dustbird.mail.ui.accounts.add.AddStep
import app.dustbird.mail.ui.accounts.add.FormField
import app.dustbird.mail.ui.accounts.add.FormRules
import app.dustbird.mail.ui.accounts.add.FormValues
import app.dustbird.mail.ui.accounts.add.SaveError
import app.dustbird.mail.ui.accounts.add.Stage
import app.dustbird.mail.ui.accounts.add.passed
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryCredentialStore : CredentialStore {
    private val secrets = ConcurrentHashMap<Long, String>()
    override fun put(accountId: Long, secret: String) { secrets[accountId] = secret }
    override fun get(accountId: Long): String? = secrets[accountId]
    override fun delete(accountId: Long) { secrets.remove(accountId) }
    override fun deleteAll() = secrets.clear()
}

private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

private fun validValues() = FormValues(
    address = "user@example.com",
    password = "app-secret",
    displayName = "Gmail",
    label = "Gmail",
    identityName = "",
    username = "user@example.com",
    imapHost = "imap.example.com",
    imapPort = "993",
    imapSecurity = TransportSecurity.TLS,
    smtpHost = "smtp.example.com",
    smtpPort = "587",
    smtpSecurity = TransportSecurity.STARTTLS,
)

@RunWith(AndroidJUnit4::class)
class AddAccountViewModelTest {
    private val secret = "app-secret"
    private lateinit var db: MailDatabase
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        container = AppContainer(
            context,
            connector = ImapConnector { _, _ -> throw ConnectException("offline") },
            smtpSender = SmtpSender { _, _, _ -> error("sending is not allowed in this test") },
            credentials = MemoryCredentialStore(),
            db = db,
        )
    }

    @After
    fun tearDown() {
        runBlocking { container.settings.clearAll() }
        db.close()
    }

    private fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        imap: CheckResult = CheckResult.Ok,
        smtp: CheckResult = CheckResult.Ok,
    ): AddAccountViewModel {
        lateinit var viewModel: AddAccountViewModel
        onMain { viewModel = AddAccountViewModel(container, handle, { _, _ -> imap }, { _, _ -> smtp }) }
        return viewModel
    }

    private fun AddAccountViewModel.openForm(providerId: String = Providers.GMAIL.id) = onMain {
        choose(providerId, "Gmail")
        acknowledge(true)
        proceed()
    }

    private fun AddAccountViewModel.fill(address: String = "user@example.com") = onMain {
        fields.address.setTextAndPlaceCursorAtEnd(address)
        fields.username.setTextAndPlaceCursorAtEnd(address)
        fields.password.setTextAndPlaceCursorAtEnd(secret)
    }

    private fun AddAccountViewModel.await(predicate: (AddAccountUiState) -> Boolean): AddAccountUiState =
        runBlocking { withTimeout(10_000) { state.first(predicate) } }

    private fun AddAccountViewModel.verifyAndWait(): AddAccountUiState {
        onMain { verify() }
        return await { it.step == AddStep.VERIFY && it.imap is Stage.Done && (!it.imap.passed || it.smtp is Stage.Done) }
    }

    @Test
    fun completeValuesAreValid() {
        assertEquals(emptySet(), FormRules.invalidFields(validValues()))
    }

    @Test
    fun eachInvalidFieldIsReported() {
        val invalid = FormRules.invalidFields(
            validValues().copy(
                address = "user@",
                password = "",
                displayName = " ",
                label = "",
                username = "",
                imapHost = "",
                imapPort = "0",
                smtpHost = "smtp example.com",
                smtpPort = "65536",
            ),
        )
        assertEquals(FormField.entries.toSet(), invalid)
    }

    @Test
    fun hostsRejectSchemesSpacesAndPorts() {
        listOf("imap.example.com", "mail", "192.0.2.10", "a-b.example.com").forEach { assertTrue(FormRules.isValidHost(it), it) }
        listOf("", " ", "imap example.com", "https://imap.example.com", "imap.example.com:993", "imap.example.com/", "-bad.example.com", "bad-.example.com", "a..b")
            .forEach { assertFalse(FormRules.isValidHost(it), it) }
    }

    @Test
    fun portsMustBeBetween1And65535() {
        assertEquals(993, FormRules.parsePort("993"))
        assertEquals(1, FormRules.parsePort("1"))
        assertEquals(65535, FormRules.parsePort("65535"))
        listOf("", "0", "65536", "-1", "+25", "99a", "9 3", "1.5").forEach { assertNull(FormRules.parsePort(it), it) }
    }

    @Test
    fun incompleteFormStaysOnFormAndShowsErrors() {
        val viewModel = newViewModel()
        viewModel.openForm()
        onMain { viewModel.verify() }
        val state = viewModel.state.value
        assertEquals(AddStep.FORM, state.step)
        assertTrue(state.showErrors)
        assertTrue(FormField.ADDRESS in FormRules.invalidFields(viewModel.fields.values()))
    }

    @Test
    fun customServiceRequiresServerFieldsAndOpensAdvancedSection() {
        val viewModel = newViewModel()
        viewModel.openForm(Providers.CUSTOM.id)
        viewModel.fill()
        assertTrue(viewModel.fields.advancedOpen)
        onMain { viewModel.fields.advancedOpen = false; viewModel.verify() }
        assertEquals(AddStep.FORM, viewModel.state.value.step)
        assertTrue(viewModel.fields.advancedOpen)
        assertTrue(FormRules.invalidFields(viewModel.fields.values()).containsAll(setOf(FormField.IMAP_HOST, FormField.SMTP_PORT)))
    }

    @Test
    fun userNameFollowsAddress() {
        val viewModel = newViewModel()
        viewModel.openForm()
        onMain {
            viewModel.fields.address.setTextAndPlaceCursorAtEnd("first@example.com")
            Snapshot.sendApplyNotifications()
        }
        runBlocking { withTimeout(10_000) { while (viewModel.fields.username.text.toString() != "first@example.com") delay(20) } }
    }

    @Test
    fun verifiedAccountIsSavedAndPasswordIsCleared() {
        val viewModel = newViewModel()
        viewModel.openForm()
        viewModel.fill()
        val verified = viewModel.verifyAndWait()
        assertEquals(Stage.Done(CheckResult.Ok), verified.imap)
        assertEquals(Stage.Done(CheckResult.Ok), verified.smtp)

        onMain { viewModel.save() }
        val saved = viewModel.await { it.step == AddStep.SYNC }
        val accountId = assertNotNull(saved.accountId)
        val account = assertNotNull(runBlocking { db.accounts().get(accountId) })
        assertEquals("imap.gmail.com", account.imapHost)
        assertEquals(StageState.OK, account.stageImapAuth)
        assertEquals(StageState.OK, account.stageSmtpAuth)
        assertEquals(StageState.UNKNOWN, account.stageSend)
        assertEquals(secret, container.credentials.get(accountId))
        assertEquals("", viewModel.fields.password.text.toString())

        val synced = viewModel.await { it.sync != null }
        assertEquals(SyncOutcome.Failed(ConnectFailure.UNREACHABLE), synced.sync)
        runBlocking { container.accounts.removeAccount(accountId, force = true) }
    }

    @Test
    fun duplicateMailboxIsReportedAndNothingIsStored() {
        val first = newViewModel()
        first.openForm()
        first.fill()
        first.verifyAndWait()
        onMain { first.save() }
        val firstId = assertNotNull(first.await { it.step == AddStep.SYNC }.accountId)
        first.await { it.sync != null }

        val second = newViewModel()
        second.openForm()
        second.fill()
        second.verifyAndWait()
        onMain { second.save() }
        val rejected = second.await { it.saveError != null && !it.busy }
        assertEquals(SaveError.Rejected(Rejection.DUPLICATE_MAILBOX), rejected.saveError)
        assertEquals(AddStep.VERIFY, rejected.step)
        assertEquals(1, runBlocking { db.accounts().getAll() }.size)
        assertEquals(secret, second.fields.password.text.toString())
        runBlocking { container.accounts.removeAccount(firstId, force = true) }
    }

    @Test
    fun failedLoginStopsBeforeSmtpAndCannotBeSaved() {
        val viewModel = newViewModel(imap = CheckResult.Failed(ConnectFailure.OTHER, "rejected $secret by server"))
        viewModel.openForm()
        viewModel.fill()
        val state = viewModel.verifyAndWait()
        val failed = assertIs<CheckResult.Failed>(assertIs<Stage.Done>(state.imap).result)
        assertEquals(ConnectFailure.OTHER, failed.failure)
        assertFalse(secret in failed.detail)
        assertEquals(Stage.Pending, state.smtp)

        onMain { viewModel.save() }
        assertEquals(AddStep.VERIFY, viewModel.state.value.step)
        assertTrue(runBlocking { db.accounts().getAll() }.isEmpty())
    }

    @Test
    fun backFromVerificationKeepsInputAndResetsResults() {
        val viewModel = newViewModel()
        viewModel.openForm()
        viewModel.fill()
        viewModel.verifyAndWait()
        onMain { viewModel.back() }
        val state = viewModel.state.value
        assertEquals(AddStep.FORM, state.step)
        assertEquals(Stage.Pending, state.imap)
        assertEquals("user@example.com", viewModel.fields.address.text.toString())
        assertEquals(secret, viewModel.fields.password.text.toString())
    }

    @Test
    fun savedStateKeepsOnlyStepAndNeverThePassword() {
        val handle = SavedStateHandle()
        val viewModel = newViewModel(handle)
        viewModel.openForm()
        viewModel.fill()
        val restored = newViewModel(handle)
        assertEquals(AddStep.FORM, restored.state.value.step)
        assertEquals(Providers.GMAIL.id, restored.state.value.providerId)
        assertEquals("", restored.fields.password.text.toString())
        assertEquals("", restored.fields.address.text.toString())
        assertTrue(handle.keys().none { handle.get<Any?>(it).toString().contains(secret) })
    }
}
