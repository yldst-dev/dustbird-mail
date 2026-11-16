package app.dustbird.mail.ui.accounts

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.R
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.provider.Providers
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.repo.DraftContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AccountDetailTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var kit: AccountManageTestKit

    @Before
    fun setUp() {
        kit = AccountManageTestKit(compose)
    }

    @After
    fun tearDown() = kit.close()

    private fun showDetail(accountId: Long) = kit.show { AccountDetailRoute(accountId = accountId, onBack = {}) }

    private fun field(@StringRes label: Int): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(kit.string(label)))

    private fun scrollAndClick(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun replaceText(@StringRes label: Int, value: String) {
        field(label).performTextClearance()
        if (value.isNotEmpty()) field(label).performTextInput(value)
    }

    @Test
    fun identityEditorExplainsEveryRejection() {
        val first = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        kit.seedAccount("second", "Home Mail", "Home", sortOrder = 1)
        showDetail(first)
        val add = kit.string(R.string.accounts_identity_add)
        kit.waitForText(add)
        scrollAndClick(add)

        replaceText(R.string.accounts_identity_address, "not-an-address")
        replaceText(R.string.accounts_identity_label, "fresh")
        compose.onNodeWithText(kit.string(R.string.accounts_save)).performClick()
        kit.waitForText(kit.string(R.string.accounts_rejection_invalid_address))

        replaceText(R.string.accounts_identity_address, "second@example.com")
        compose.onNodeWithText(kit.string(R.string.accounts_save)).performClick()
        kit.waitForText(kit.string(R.string.accounts_rejection_duplicate_address))

        replaceText(R.string.accounts_identity_address, "fresh@example.com")
        replaceText(R.string.accounts_identity_label, "second")
        compose.onNodeWithText(kit.string(R.string.accounts_save)).performClick()
        kit.waitForText(kit.string(R.string.accounts_rejection_duplicate_label))

        replaceText(R.string.accounts_identity_label, "")
        compose.onNodeWithText(kit.string(R.string.accounts_save)).performClick()
        kit.waitForText(kit.string(R.string.accounts_rejection_blank_label))

        replaceText(R.string.accounts_identity_label, "fresh")
        compose.onNodeWithText(kit.string(R.string.accounts_save)).performClick()
        val title = kit.string(R.string.accounts_identity_add_title)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        val addresses = runBlocking { kit.db.identities().forAccount(first).map { it.address } }
        assertTrue("fresh@example.com" in addresses)
    }

    @Test
    fun lastIdentityIsRefusedWithAnExplanation() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        showDetail(id)
        kit.waitForText(kit.string(R.string.accounts_identity_add))
        val delete = kit.string(R.string.accounts_identity_action_desc, "first", kit.string(R.string.accounts_identity_delete))
        compose.onNodeWithContentDescription(delete).performScrollTo().performClick()
        compose.onNodeWithText(kit.string(R.string.accounts_identity_delete_confirm)).performClick()
        kit.waitForText(kit.string(R.string.accounts_rejection_last_identity))
        assertEquals(1, runBlocking { kit.db.identities().forAccount(id).size })
    }

    @Test
    fun blockedDeleteAsksAgainAndNamesWhatWouldBeLost() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        val identityId = runBlocking { kit.db.identities().forAccount(id).first().id }
        runBlocking {
            kit.container.drafts.save(
                DraftContent(
                    id = null,
                    identityId = identityId,
                    to = emptyList(),
                    cc = emptyList(),
                    bcc = emptyList(),
                    subject = "draft",
                    body = "",
                    inReplyTo = null,
                    references = emptyList(),
                ),
            )
        }
        showDetail(id)
        val delete = kit.string(R.string.accounts_delete)
        kit.waitForText(delete)
        scrollAndClick(delete)
        compose.onNodeWithText(kit.string(R.string.accounts_delete_confirm)).performClick()
        kit.waitForText(kit.string(R.string.accounts_delete_blocked_text, 0, 1))
        assertNotNull(runBlocking { kit.container.accounts.account(id) })
        compose.onNodeWithText(kit.string(R.string.accounts_cancel)).performClick()
        assertNotNull(runBlocking { kit.container.accounts.account(id) })
    }

    @Test
    fun accountNeedingReauthShowsTheMaskedPasswordFormFirst() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0, needsReauth = true)
        showDetail(id)
        kit.waitForText(kit.string(R.string.accounts_reauth_title))
        compose.onNodeWithText(kit.string(R.string.accounts_password)).assertExists()
        compose.onAllNodesWithText(kit.string(R.string.accounts_reauth_submit)).assertCountEquals(1)
        compose.onNodeWithText(kit.string(R.string.accounts_reauth_submit)).assertIsNotEnabled()
    }

    @Test
    fun syncModeTextStatesTheRealDelay() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0, syncMode = SyncMode.PERIODIC)
        showDetail(id)
        kit.waitForText(kit.string(R.string.accounts_sync_periodic_desc))
        scrollAndClick(kit.string(R.string.accounts_sync_idle))
        compose.onNodeWithText(kit.string(R.string.accounts_sync_idle_desc)).assertExists()
        scrollAndClick(kit.string(R.string.accounts_sync_manual))
        compose.onNodeWithText(kit.string(R.string.accounts_sync_manual_desc)).assertExists()
    }

    @Test
    fun naverAccountMentionsTheMissingIdleAdvertisement() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0, providerId = Providers.NAVER.id)
        showDetail(id)
        kit.waitForText(kit.string(R.string.accounts_sync_naver_note))
    }

    @Test
    fun folderRolesShowAssignmentsCandidatesAndAcceptAConfirmation() {
        val id = kit.seedAccount("first", "Work Mail", "Work", sortOrder = 0)
        runBlocking {
            listOf("INBOX" to "\\HasNoChildren", "Sent Items" to "\\Sent", "Drafts" to "").forEach { (path, attributes) ->
                kit.db.folders().insert(
                    FolderEntity(
                        accountId = id,
                        path = path,
                        delimiter = "/",
                        attributes = attributes,
                        role = null,
                        roleConfirmed = false,
                        uidValidity = 0,
                        highestUid = 0,
                        baselineComplete = false,
                        syncedAt = 0,
                    ),
                )
            }
        }
        showDetail(id)
        kit.waitForText(kit.string(R.string.accounts_role_assigned, "INBOX"))
        compose.onNodeWithText(kit.string(R.string.accounts_role_assigned, "Sent Items")).assertExists()
        compose.onNodeWithText(kit.string(R.string.accounts_role_candidates, "Drafts")).assertExists()
        compose.onNodeWithText(kit.string(R.string.accounts_role_missing)).assertExists()

        val pick = kit.string(R.string.accounts_role_pick_desc, kit.string(R.string.accounts_role_drafts))
        compose.onNodeWithContentDescription(pick).performScrollTo().performClick()
        compose.onNodeWithText(kit.string(R.string.accounts_role_pick_candidate, "Drafts")).performClick()
        kit.waitForText(kit.string(R.string.accounts_role_assigned, "Drafts"))
        compose.onNodeWithText(kit.string(R.string.accounts_role_source_suggestion)).assertExists()
    }
}
