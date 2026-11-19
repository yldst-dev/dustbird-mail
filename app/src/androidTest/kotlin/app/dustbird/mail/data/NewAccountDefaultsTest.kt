package app.dustbird.mail.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.AppContainer
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.NewAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class NewAccountDefaultsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
    private val container = AppContainer(context, db = db)
    private val imap = ServerEndpoint("imap.example.test", 993, TransportSecurity.TLS)
    private val smtp = ServerEndpoint("smtp.example.test", 587, TransportSecurity.STARTTLS)

    @After
    fun close() = db.close()

    private fun add(providerId: String, address: String): SyncMode = runBlocking {
        val added = assertIs<AddAccountResult.Added>(container.accounts.addAccount(NewAccount(providerId, "name", "label", address, address, "", imap, smtp), "secret"))
        try {
            db.accounts().get(added.accountId)!!.syncMode
        } finally {
            container.accounts.removeAccount(added.accountId, force = true)
        }
    }

    @Test
    fun theReceivedAddressLabelIsTheOneChosenAtSetupOrTheAddressPrefix() = runBlocking<Unit> {
        fun input(address: String, label: String) = NewAccount("custom", "name", "label", address, address, "", imap, smtp, identityLabel = label)
        val chosen = assertIs<AddAccountResult.Added>(container.accounts.addAccount(input("shop@custom.example", "  쇼핑 "), "secret"))
        val automatic = assertIs<AddAccountResult.Added>(container.accounts.addAccount(input("work@other.example", ""), "secret"))
        val labels = db.identities().getAll().associate { it.accountId to it.label }
        assertEquals("쇼핑", labels[chosen.accountId])
        assertEquals("work", labels[automatic.accountId])
        assertIs<AddAccountResult.Rejected>(container.accounts.addAccount(input("third@third.example", "쇼핑"), "secret"))
    }

    @Test
    fun anICloudAccountStartsInRealTimeAndOtherServicesStayPeriodic() {
        assertEquals(SyncMode.IDLE, add("icloud", "primary@icloud.example"))
        assertEquals(SyncMode.PERIODIC, add("gmail", "someone@gmail.example"))
        assertEquals(SyncMode.PERIODIC, add("custom", "someone@custom.example"))
    }
}
