package app.dustbird.mail

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchShowsAppName() {
        compose.onNodeWithText(compose.activity.getString(R.string.add_account_title_choose)).assertExists()
    }

    @Test
    fun imapLoginOverTlsToHostServer() {
        val props = Properties().apply {
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.checkserveridentity", "true")
            put("mail.imap.connectiontimeout", "10000")
            put("mail.imap.timeout", "10000")
        }
        val store = Session.getInstance(props).getStore("imap")
        store.connect("10.0.2.2", 3993, "tester@example.test", "tester-pw")
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        assertTrue(inbox.messageCount >= 0)
        inbox.close(false)
        store.close()
    }
}
