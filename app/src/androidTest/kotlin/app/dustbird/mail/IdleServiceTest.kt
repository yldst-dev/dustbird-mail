package app.dustbird.mail

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.NewAccount
import app.dustbird.mail.idle.IdleService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class IdleServiceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = (context.applicationContext as MailApplication).container
    private var accountId = 0L

    @Before
    fun setUp() {
        assumeTrue("host test server is not running", runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 3993), 2000) } }.isSuccess)
        runBlocking { container.maintenance.resetEverything() }
    }

    @After
    fun tearDown() = runBlocking {
        context.stopService(Intent(context, IdleService::class.java))
        if (accountId != 0L) container.accounts.removeAccount(accountId, force = true)
    }

    @Test
    fun realTimeAccountDeliversANotificationWithoutPolling() = runBlocking {
        val imap = ServerEndpoint("10.0.2.2", 3993, TransportSecurity.TLS)
        val smtp = ServerEndpoint("10.0.2.2", 3465, TransportSecurity.TLS)
        val added = container.accounts.addAccount(NewAccount("custom", "idle", "idle", "tester@example.test", "tester@example.test", "", imap, smtp), "tester-pw")
        accountId = assertIs<AddAccountResult.Added>(added).accountId
        container.sync.syncAccount(accountId)
        container.accounts.updateAccount(accountId, "idle", "idle", "tester@example.test", imap, smtp, SyncMode.IDLE, true)
        ContextCompat.startForegroundService(context, Intent(context, IdleService::class.java))
        delay(4000)

        val session = Session.getInstance(Properties().apply {
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.auth", "true")
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress("friend@example.test"))
            setRecipient(Message.RecipientType.TO, InternetAddress("tester@example.test"))
            setSubject("실시간 수신 시험 ${System.nanoTime()}", "UTF-8")
            setText("본문", "UTF-8")
        }
        session.getTransport("smtp").apply {
            connect("10.0.2.2", 3465, "friend@example.test", "friend-pw")
            sendMessage(message, message.allRecipients)
            close()
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        var shown = false
        repeat(20) {
            if (!shown) {
                delay(1000)
                shown = manager.activeNotifications.any { it.tag == "account_$accountId" && it.notification.extras.getCharSequence("android.text")?.contains("실시간 수신 시험") == true }
            }
        }
        assertTrue(shown, "no notification within 20 seconds")
        assertTrue(container.accounts.accounts.first().any { it.id == accountId && it.syncMode == SyncMode.IDLE })
    }
}
