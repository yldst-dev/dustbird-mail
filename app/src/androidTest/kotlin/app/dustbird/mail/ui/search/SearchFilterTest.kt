package app.dustbird.mail.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.data.db.FolderEntity
import app.dustbird.mail.data.repo.SearchField
import app.dustbird.mail.data.repo.SearchFilter
import app.dustbird.mail.ui.mailbox.TestMailbox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFilterTest {
    private lateinit var seed: TestMailbox
    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()
    private var work = 0L
    private var home = 0L

    @Before
    fun setUp() = runBlocking {
        seed = TestMailbox()
        work = seed.account("work", "업무")
        home = seed.account("home", "개인", sortOrder = 1)
        val workInbox = seed.inbox(work)
        val homeInbox = seed.inbox(home)
        val workSent = seed.db.folders().insert(
            FolderEntity(
                accountId = work, path = "Sent", delimiter = "/", attributes = "", role = FolderRole.SENT, roleConfirmed = true,
                uidValidity = 1, highestUid = 0, baselineComplete = true, syncedAt = 0,
            ),
        )
        mail(work, workInbox, 1, "분기 보고서 검토", age = 0, seen = false, attachment = true)
        mail(work, workInbox, 2, "보고서 초안 공유", age = 3, seen = true, flagged = true)
        mail(work, workSent, 3, "보고서 보냈습니다", age = 10, seen = true)
        mail(home, homeInbox, 4, "주말 보고 모임", age = 40, seen = false)
    }

    @After
    fun tearDown() = seed.close()

    private suspend fun mail(account: Long, folder: Long, uid: Long, subject: String, age: Int, seen: Boolean, attachment: Boolean = false, flagged: Boolean = false) {
        val id = seed.message(account, folder, uid, subject, fromName = "보낸이$uid", seen = seen, hasAttachment = attachment)
        val row = seed.db.messages().get(id)!!
        seed.db.messages().upsert(row.copy(sortDate = now - age * day - uid * 1000, flagged = flagged))
    }

    private fun found(query: String, filter: SearchFilter = SearchFilter()) = runBlocking {
        seed.container.mail.search(query, filter).first().map { it.subject }
    }

    @Test
    fun theTimeWindowKeepsOnlyMailInsideIt() {
        assertEquals(listOf("분기 보고서 검토", "보고서 초안 공유"), found("보고", SearchFilter(since = now - 7 * day)))
        assertEquals(listOf("보고서 보냈습니다"), found("보고", SearchFilter(since = now - 20 * day, until = now - 5 * day)))
        assertEquals(listOf("주말 보고 모임"), found("", SearchFilter(until = now - 30 * day)))
    }

    @Test
    fun folderAccountAndStateFiltersCombine() {
        assertEquals(listOf("보고서 보냈습니다"), found("보고", SearchFilter(role = FolderRole.SENT)))
        assertEquals(listOf("주말 보고 모임"), found("보고", SearchFilter(accountId = home)))
        assertEquals(listOf("분기 보고서 검토", "주말 보고 모임"), found("", SearchFilter(unread = true)))
        assertEquals(listOf("보고서 초안 공유"), found("", SearchFilter(starred = true)))
        assertEquals(listOf("분기 보고서 검토"), found("", SearchFilter(attachment = true, role = FolderRole.INBOX)))
    }

    @Test
    fun theFieldChoiceNarrowsWhereTheWordIsLookedFor() {
        assertEquals(listOf("분기 보고서 검토"), found("보낸이1", SearchFilter(field = SearchField.SENDER)))
        assertEquals(emptyList<String>(), found("보낸이1", SearchFilter(field = SearchField.SUBJECT)))
        assertEquals(listOf("보고서 초안 공유"), found("초안", SearchFilter(field = SearchField.SUBJECT)))
    }

    @Test
    fun anEmptyQueryWithoutFiltersStillMatchesEverythingForTheRepository() {
        assertEquals(4, found("").size)
    }
}
