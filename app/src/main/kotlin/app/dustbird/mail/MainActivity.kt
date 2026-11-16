package app.dustbird.mail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.nav.MailApp
import app.dustbird.mail.ui.theme.DustbirdTheme

class MainActivity : ComponentActivity() {
    private var openMessageId by mutableStateOf<Long?>(null)
    private var openAccountId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readExtras(intent)
        val container = (application as MailApplication).container
        setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(Modifier.fillMaxSize()) { MailApp(openMessageId, openAccountId) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
    }

    private fun readExtras(intent: Intent?) {
        openMessageId = intent?.getLongExtra(EXTRA_MESSAGE_ID, -1L)?.takeIf { it >= 0 }
        openAccountId = intent?.getLongExtra(EXTRA_ACCOUNT_ID, -1L)?.takeIf { it >= 0 }
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "app.dustbird.mail.extra.MESSAGE_ID"
        const val EXTRA_ACCOUNT_ID = "app.dustbird.mail.extra.ACCOUNT_ID"
    }
}
