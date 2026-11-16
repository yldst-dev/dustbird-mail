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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openMessageId = messageIdFrom(intent)
        val container = (application as MailApplication).container
        setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(Modifier.fillMaxSize()) { MailApp(openMessageId) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openMessageId = messageIdFrom(intent)
    }

    private fun messageIdFrom(intent: Intent?): Long? = intent?.getLongExtra(EXTRA_MESSAGE_ID, -1L)?.takeIf { it >= 0 }

    companion object {
        const val EXTRA_MESSAGE_ID = "app.dustbird.mail.extra.MESSAGE_ID"
    }
}
