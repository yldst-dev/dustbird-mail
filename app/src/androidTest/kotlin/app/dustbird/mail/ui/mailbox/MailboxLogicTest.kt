package app.dustbird.mail.ui.mailbox

import app.dustbird.mail.core.identity.RecipientMatch
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.net.ConnectFailure
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.MessageSummary
import app.dustbird.mail.sync.AccountSyncState
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MailboxLogicTest {
    private val endpoint = ServerEndpoint("mail.example.test", 993, TransportSecurity.TLS)

    private fun account(id: Long, needsReauth: Boolean = false) = MailAccount(
        id = id, providerId = "custom", displayName = "Account $id", label = "Label $id", username = "user$id@example.test",
        imap = endpoint, smtp = endpoint, authMethod = AuthMethod.APP_PASSWORD, sortOrder = id.toInt(), syncMode = SyncMode.MANUAL,
        notificationsEnabled = false, needsReauth = needsReauth,
    )

    private fun summary(id: Long) = MessageSummary(
        id = id, accountId = 1, subject = "s", fromName = null, fromAddress = null, preview = "", sortDate = 0,
        seen = false, hasAttachment = false, match = RecipientMatch.Unknown,
    )

    private fun state(
        messages: List<MessageSummary>?,
        accounts: List<MailAccount> = listOf(account(1), account(2)),
        problems: List<AccountProblem> = emptyList(),
        busy: Boolean = false,
    ) = MailboxUiState(
        scope = MailboxScope.Unified, accounts = accounts, scopeAccounts = accounts, identityLabels = emptyMap(), messages = messages,
        unread = emptyMap(), problems = problems, busy = busy, refreshing = false, loadingOlder = false, hasMore = false,
        outboxPending = 0, selectedId = null,
    )

    @Test
    fun bodyDistinguishesLoadingEmptyFailureOfflineAndList() {
        val failed = listOf(AccountProblem(account(1), ConnectFailure.UNREACHABLE), AccountProblem(account(2), null))
        assertEquals(MailboxBody.LOADING, state(null).body(online = true))
        assertEquals(MailboxBody.LOADING, state(emptyList(), busy = true).body(online = true))
        assertEquals(MailboxBody.EMPTY, state(emptyList()).body(online = true))
        assertEquals(MailboxBody.OFFLINE, state(emptyList()).body(online = false))
        assertEquals(MailboxBody.FAILED, state(emptyList(), problems = failed).body(online = true))
        assertEquals(MailboxBody.EMPTY, state(emptyList(), problems = failed.take(1)).body(online = true))
        assertEquals(MailboxBody.MESSAGES, state(listOf(summary(1)), problems = failed).body(online = false))
        assertEquals(MailboxBody.NO_ACCOUNTS, state(emptyList(), accounts = emptyList()).body(online = true))
    }

    @Test
    fun problemsComeFromSyncStateOrThePersistedReauthFlag() {
        val accounts = listOf(account(1), account(2), account(3, needsReauth = true), account(4))
        val states = mapOf(
            1L to AccountSyncState.Error(ConnectFailure.TLS),
            2L to AccountSyncState.NeedsReauth,
            3L to AccountSyncState.Syncing,
            4L to AccountSyncState.Idle,
        )
        val problems = problemsOf(accounts, states)
        assertEquals(listOf(1L, 2L, 3L), problems.map { it.account.id })
        assertEquals(listOf(ConnectFailure.TLS, null, null), problems.map { it.failure })
    }

    @Test
    fun olderTargetsFollowTheScope() {
        val accounts = listOf(account(1), account(2))
        assertEquals(listOf(FolderKey(1, FolderRole.INBOX), FolderKey(2, FolderRole.INBOX)), olderTargetsOf(MailboxScope.Unified, accounts))
        assertEquals(listOf(FolderKey(2, FolderRole.SENT)), olderTargetsOf(MailboxScope.Account(2, FolderRole.SENT), accounts))
        assertEquals(listOf(2L), scopeAccountsOf(MailboxScope.Account(2, FolderRole.SENT), accounts).map { it.id })
    }

    @Test
    fun timeFormatFollowsTodayThisYearAndOlder() {
        val zone = ZoneId.of("UTC")
        val formatter = MessageTimeFormatter("a h:mm", "M/d", "yyyy/M/d", zone)
        val now = Instant.parse("2026-11-16T15:42:00Z")
        val today = formatter.format(Instant.parse("2026-11-16T09:05:00Z").toEpochMilli(), now)
        assertTrue(today.endsWith("9:05"))
        assertEquals("3/4", formatter.format(Instant.parse("2026-03-04T09:05:00Z").toEpochMilli(), now))
        assertEquals("2025/3/4", formatter.format(Instant.parse("2025-03-04T09:05:00Z").toEpochMilli(), now))
        assertEquals("11/15", formatter.format(Instant.parse("2026-11-15T23:59:00Z").toEpochMilli(), now))
    }
}
