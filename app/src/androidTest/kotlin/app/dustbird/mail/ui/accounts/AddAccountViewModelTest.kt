package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.R
import app.dustbird.mail.agent.callItem
import app.dustbird.mail.core.agent.AgentBackendException
import app.dustbird.mail.core.agent.AgentItem
import app.dustbird.mail.core.agent.AgentRequest
import app.dustbird.mail.core.agent.BackendFailure
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.CheckResult
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.core.net.ImapConnector
import app.dustbird.mail.core.net.SmtpSender
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.sync.SyncOutcome
import app.dustbird.mail.ui.accounts.add.AddAccountUiState
import app.dustbird.mail.ui.accounts.add.AddAccountViewModel
import app.dustbird.mail.ui.accounts.add.AddStep
import app.dustbird.mail.ui.accounts.add.AssistState
import app.dustbird.mail.ui.accounts.add.FormField
import app.dustbird.mail.ui.accounts.add.FormRules
import app.dustbird.mail.ui.accounts.add.FormValues
import app.dustbird.mail.ui.accounts.add.SaveError
import app.dustbird.mail.ui.accounts.add.RolePrompt
import app.dustbird.mail.ui.accounts.add.Stage
import app.dustbird.mail.ui.accounts.add.rolePrompts
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
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

private fun folder(path: String, attributes: String = "", role: FolderRole? = null, confirmed: Boolean = false) = FolderEntity(
    accountId = 1, path = path, delimiter = "/", attributes = attributes, role = role, roleConfirmed = confirmed,
    uidValidity = 1, highestUid = 0, baselineComplete = false, syncedAt = 0,
)

private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

private fun validValues() = FormValues(
    address = "user@example.com",
    password = "app-secret",
    displayName = "Gmail",
    label = "Gmail",
    identityName = "",
    identityLabel = "",
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
    private val agentRequests = CopyOnWriteArrayList<AgentRequest>()
    private var agentReply: (AgentRequest) -> List<AgentItem.Model> = { error("the agent is not used in this test") }

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
            agentBackend = { request, _ ->
                agentRequests += request
                agentReply(request)
            },
        )
    }

    @After
    fun tearDown() {
        runBlocking { db.accounts().getAll().forEach { container.accounts.removeAccount(it.id, force = true) } }
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
        return await { it.step == AddStep.VERIFY && it.imap is Stage.Done && it.smtp is Stage.Done }
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
    }

    @Test
    fun duplicateMailboxIsReportedAndNothingIsStored() {
        val first = newViewModel()
        first.openForm()
        first.fill()
        first.verifyAndWait()
        onMain { first.save() }
        first.await { it.step == AddStep.SYNC }
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
    }

    @Test
    fun failedLoginIsReportedSeparatelyFromSmtpAndCannotBeSaved() {
        val viewModel = newViewModel(imap = CheckResult.Failed(ConnectFailure.OTHER, "rejected $secret by server"))
        viewModel.openForm()
        viewModel.fill()
        val state = viewModel.verifyAndWait()
        val failed = assertIs<CheckResult.Failed>(assertIs<Stage.Done>(state.imap).result)
        assertEquals(ConnectFailure.OTHER, failed.failure)
        assertFalse(secret in failed.detail)
        assertEquals(Stage.Done(CheckResult.Ok), state.smtp)

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

    @Test
    fun sentenceEntryFillsOnlyValidValuesAndNeverTouchesThePassword() {
        val viewModel = newViewModel()
        viewModel.openForm(Providers.CUSTOM.id)
        onMain { viewModel.fields.password.setTextAndPlaceCursorAtEnd(secret) }
        agentReply = {
            listOf(
                callItem(
                    "call-1", "fill_account_form",
                    JSONObject()
                        .put("address", "kim@corp.example")
                        .put("display_name", "회사\n메일")
                        .put("label", "회사")
                        .put("username", JSONObject.NULL)
                        .put("imap_host", "imap.corp.example")
                        .put("imap_port", 993)
                        .put("imap_security", "TLS")
                        .put("smtp_host", "http://smtp.corp.example")
                        .put("smtp_port", 70000)
                        .put("smtp_security", "PLAIN")
                        .put("password", "leaked-by-the-model"),
                ),
            )
        }

        onMain { viewModel.assist("  회사 메일 kim@corp.example, 받는 서버 imap.corp.example 993$secret  ") }
        val done = assertIs<AssistState.Done>(viewModel.await { it.assist is AssistState.Done }.assist)

        val request = agentRequests.single()
        assertEquals(listOf(AgentItem.User("회사 메일 kim@corp.example, 받는 서버 imap.corp.example 993")), request.items)
        assertEquals(listOf("fill_account_form"), request.tools.map { it.name })
        assertFalse("password" in request.tools.single().parameters)
        assertTrue("Never put a password" in request.instructions)
        assertEquals(
            listOf(R.string.add_account_field_address, R.string.add_account_field_display_name, R.string.add_account_field_label, R.string.add_account_section_imap),
            done.filled,
        )
        val values = viewModel.fields.values()
        assertEquals("kim@corp.example", values.address)
        assertEquals("회사 메일", values.displayName)
        assertEquals("회사", values.label)
        assertEquals("imap.corp.example", values.imapHost)
        assertEquals("993", values.imapPort)
        assertEquals(TransportSecurity.TLS, values.imapSecurity)
        assertEquals("", values.smtpHost)
        assertEquals("", values.smtpPort)
        assertEquals(TransportSecurity.STARTTLS, values.smtpSecurity)
        assertEquals(secret, values.password)
        assertTrue(viewModel.fields.advancedOpen)
        assertEquals(AddStep.FORM, viewModel.state.value.step)
        val log = runBlocking { db.agent().observeLog(10).first() }.single()
        assertEquals("fill_account_form", log.tool)
        assertEquals("", log.summary)
    }

    @Test
    fun sentenceEntryFailureIsReportedAndLeavesTheFormAlone() {
        val viewModel = newViewModel()
        viewModel.openForm()
        agentReply = { throw AgentBackendException(BackendFailure.NOT_CONFIGURED, "off") }

        onMain { viewModel.assist("gmail user@example.com") }
        assertEquals(BackendFailure.NOT_CONFIGURED, (viewModel.await { it.assist is AssistState.Failed }.assist as AssistState.Failed).failure)
        assertEquals("", viewModel.fields.values().address)
        assertEquals("imap.gmail.com", viewModel.fields.values().imapHost)

        agentReply = { emptyList() }
        onMain { viewModel.dismissAssist() }
        assertEquals(AssistState.Idle, viewModel.state.value.assist)
        onMain { viewModel.assist("gmail user@example.com") }
        assertEquals(BackendFailure.INCOMPLETE, (viewModel.await { it.assist is AssistState.Failed }.assist as AssistState.Failed).failure)
        assertTrue(runBlocking { db.agent().observeLog(10).first() }.isEmpty())
    }

    @Test
    fun sentenceEntryCannotMoveAKnownServiceToAnotherServer() {
        val viewModel = newViewModel()
        viewModel.openForm()
        agentReply = {
            listOf(
                callItem(
                    "call-1", "fill_account_form",
                    JSONObject().put("address", "user@example.com").put("imap_host", "imap.other.example").put("smtp_port", 25).put("smtp_security", "TLS"),
                ),
            )
        }

        onMain { viewModel.assist("user@example.com 서버는 imap.other.example") }
        val done = assertIs<AssistState.Done>(viewModel.await { it.assist is AssistState.Done }.assist)

        assertEquals(listOf(R.string.add_account_field_address), done.filled)
        assertTrue("leave every imap_* and smtp_* field out" in agentRequests.single().instructions)
        val values = viewModel.fields.values()
        assertEquals("user@example.com", values.address)
        assertEquals("imap.gmail.com", values.imapHost)
        assertEquals("465", values.smtpPort)
        assertFalse(viewModel.fields.advancedOpen)
        onMain { viewModel.dismissAssist() }
        assertEquals(done, viewModel.state.value.assist)
    }

    @Test
    fun assignedFoldersAreNotAskedAbout() {
        val folders = listOf(
            folder("INBOX"),
            folder("[Gmail]/Sent Mail", "\\Sent"),
            folder("[Gmail]/Drafts", "\\Drafts"),
            folder("[Gmail]/Spam", "\\Junk"),
        )
        assertEquals(emptyList(), rolePrompts(folders))
    }

    @Test
    fun foldersMatchedOnlyByNameNeedConfirmation() {
        val prompts = rolePrompts(listOf(folder("INBOX"), folder("Sent Messages"), folder("Drafts"), folder("Spam")))
        assertEquals(
            listOf(
                RolePrompt(FolderRole.SENT, listOf("Sent Messages"), missing = false),
                RolePrompt(FolderRole.DRAFTS, listOf("Drafts"), missing = false),
                RolePrompt(FolderRole.SPAM, listOf("Spam"), missing = false),
            ),
            prompts,
        )
    }

    @Test
    fun missingRoleOffersOnlyFoldersThatAreNotAssigned() {
        val prompts = rolePrompts(
            listOf(folder("INBOX"), folder("Outgoing", "\\Sent"), folder("Work"), folder("Trash", "\\Trash"), folder("Confirmed", role = FolderRole.SPAM, confirmed = true)),
        )
        assertEquals(listOf(FolderRole.DRAFTS), prompts.map { it.role })
        assertEquals(listOf("Work"), prompts.single().options)
        assertTrue(prompts.single().missing)
    }
}
