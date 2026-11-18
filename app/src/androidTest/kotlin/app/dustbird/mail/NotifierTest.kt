package app.dustbird.mail

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.notify.AndroidNotifier
import app.dustbird.mail.sync.NewMail
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
class NotifierTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val settings = SettingsRepository(context)
    private val notifier = AndroidNotifier(context, settings)
    private val account = MailAccount(
        id = 9201, providerId = "custom", displayName = "업무 계정", label = "업무", username = "u@example.test",
        imap = ServerEndpoint("h", 993, TransportSecurity.TLS), smtp = ServerEndpoint("h", 587, TransportSecurity.STARTTLS),
        authMethod = AuthMethod.APP_PASSWORD, sortOrder = 0, syncMode = SyncMode.MANUAL, notificationsEnabled = true,
    )

    @Before
    @After
    fun clean() {
        notifier.cancel(account.id)
        notifier.prune(emptySet())
        runBlocking { settings.clearAll() }
    }

    private fun mine() = manager.activeNotifications.filter { it.tag == "account_${account.id}" }

    private fun await(condition: () -> Boolean) {
        repeat(40) {
            if (condition()) return
            Thread.sleep(100)
        }
    }

    @Test
    fun showsAccountIdentitySenderAndSubjectAndGroupsPerAccount() {
        notifier.notifyNew(account, listOf(NewMail(11, "보낸 사람", "제목입니다", "미리보기", "biz"), NewMail(12, "두 번째", "두 번째 제목", "", null)))
        await { mine().size == 3 }
        val active = mine()
        assertEquals(3, active.size)
        val first = active.first { it.id == 11 }.notification
        assertEquals("보낸 사람", first.extras.getCharSequence("android.title").toString())
        assertEquals("제목입니다", first.extras.getCharSequence("android.text").toString())
        assertEquals(listOf("읽음", "보관", "삭제"), first.actions.map { it.title.toString() })
        assertTrue(first.extras.getCharSequence("android.subText").toString().contains("업무"))
        assertTrue(first.extras.getCharSequence("android.subText").toString().contains("biz"))
        assertTrue(active.first { it.id == 12 }.notification.extras.getCharSequence("android.subText").toString().contains("미확인"))
        assertTrue(active.any { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 })
        assertNotNull(manager.getNotificationChannel("mail_account_${account.id}"))
    }

    @Test
    fun cancellingTheLastMailAlsoRemovesTheGroupSummary() {
        notifier.notifyNew(account, listOf(NewMail(51, "a", "b", "", null), NewMail(52, "c", "d", "", null)))
        await { mine().size == 3 }
        AndroidNotifier.cancelMessage(context, account.id, 51)
        await { mine().size == 2 }
        assertEquals(2, mine().size)
        AndroidNotifier.cancelMessage(context, account.id, 52)
        await { mine().isEmpty() }
        assertTrue(mine().isEmpty())
    }

    @Test
    fun lockScreenShowsOnlyNeutralTextWhenHidingIsOn() {
        notifier.notifyNew(account, listOf(NewMail(21, "비밀 발신자", "비밀 제목", "", "me")))
        await { mine().any { it.id == 21 } }
        val notification = mine().first { it.id == 21 }.notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicText = notification.publicVersion.extras.getCharSequence("android.text").toString()
        assertTrue(!publicText.contains("비밀"))
        assertTrue(publicText.contains("업무"))
    }

    @Test
    fun lockScreenContentCanBeShownWhenTheUserTurnsHidingOff() {
        runBlocking { settings.setHideLockScreenContent(false) }
        notifier.notifyNew(account, listOf(NewMail(31, "발신자", "제목", "", "me")))
        await { mine().any { it.id == 31 } }
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, mine().first { it.id == 31 }.notification.visibility)
    }

    @Test
    fun postingTheSameMailTwiceDoesNotDuplicateItAndCancelRemovesTheAccount() {
        val mail = NewMail(41, "a", "b", "", null)
        notifier.notifyNew(account, listOf(mail))
        notifier.notifyNew(account, listOf(mail))
        await { mine().any { it.id == 41 } }
        assertEquals(1, mine().count { it.id == 41 })
        notifier.cancel(account.id)
        await { mine().isEmpty() }
        assertEquals(0, mine().size)
    }

    @Test
    fun reauthNotificationNamesTheAccount() {
        notifier.reauthRequired(account)
        await { mine().isNotEmpty() }
        val notification = mine().single().notification
        assertTrue(notification.extras.getCharSequence("android.title").toString().contains("업무"))
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun staleAccountChannelsArePruned() {
        notifier.notifyNew(account, listOf(NewMail(51, "a", "b", "", null)))
        await { manager.getNotificationChannel("mail_account_${account.id}") != null }
        assertNotNull(manager.getNotificationChannel("mail_account_${account.id}"))
        notifier.prune(emptySet())
        assertEquals(null, manager.getNotificationChannel("mail_account_${account.id}"))
    }
}
