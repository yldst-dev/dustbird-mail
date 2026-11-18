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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import app.dustbird.mail.ui.LocalAppContainer
import app.dustbird.mail.ui.nav.MailApp
import app.dustbird.mail.ui.theme.DustbirdTheme
import app.dustbird.mail.ui.nav.ComposePrefill
import app.dustbird.mail.core.identity.Mailto
import app.dustbird.mail.core.identity.Addresses
import androidx.core.content.IntentCompat
import android.net.Uri

class MainActivity : ComponentActivity() {
    private var openMessageId by mutableStateOf<Long?>(null)
    private var openAccountId by mutableStateOf<Long?>(null)
    private var composeRequest by mutableIntStateOf(0)
    private var openAgentSession by mutableStateOf<Long?>(null)
    private var targetRequest by mutableIntStateOf(0)
    private var outboxRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val peakRefreshRate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
        window.attributes = window.attributes.apply { preferredRefreshRate = peakRefreshRate }
        val container = (application as MailApplication).container
        if (savedInstanceState == null) readExtras(intent)
        setContent {
            DustbirdTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(Modifier.fillMaxSize().preferredFrameRate(peakRefreshRate)) { MailApp(openMessageId, openAccountId, composeRequest, openAgentSession, targetRequest, outboxRequest) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as MailApplication).container.resumeIdle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
    }

    private fun readExtras(intent: Intent?) {
        readTargets(intent)
        prefillOf(intent)?.let {
            (application as MailApplication).container.prefill.value = it
            composeRequest++
        }
    }

    private fun readTargets(intent: Intent?) {
        openMessageId = intent?.getLongExtra(EXTRA_MESSAGE_ID, -1L)?.takeIf { it >= 0 }
        openAccountId = intent?.getLongExtra(EXTRA_ACCOUNT_ID, -1L)?.takeIf { it >= 0 }
        openAgentSession = intent?.getLongExtra(EXTRA_AGENT_SESSION, -1L)?.takeIf { it >= 0 }
        if (openMessageId != null || openAccountId != null || openAgentSession != null) targetRequest++
        if (intent?.getBooleanExtra(EXTRA_OPEN_OUTBOX, false) == true) outboxRequest++
    }

    private fun prefillOf(intent: Intent?): ComposePrefill? {
        intent ?: return null
        fun addresses(key: String) = intent.getStringArrayExtra(key).orEmpty().flatMap { Addresses.parseList(it) }
        fun shared(streams: List<Uri>) = ComposePrefill(
            to = addresses(Intent.EXTRA_EMAIL),
            cc = addresses(Intent.EXTRA_CC),
            bcc = addresses(Intent.EXTRA_BCC),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
            body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
            streams = streams,
        )
        return when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> intent.dataString?.let(Mailto::parse)?.let { link ->
                val extra = shared(emptyList())
                ComposePrefill(link.to + extra.to, link.cc + extra.cc, link.bcc + extra.bcc, link.subject.ifBlank { extra.subject }, link.body.ifBlank { extra.body })
            }
            Intent.ACTION_SEND -> shared(listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)))
            Intent.ACTION_SEND_MULTIPLE -> shared(IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            else -> null
        }
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "app.dustbird.mail.extra.MESSAGE_ID"
        const val EXTRA_ACCOUNT_ID = "app.dustbird.mail.extra.ACCOUNT_ID"
        const val EXTRA_AGENT_SESSION = "app.dustbird.mail.extra.AGENT_SESSION"
        const val EXTRA_OPEN_OUTBOX = "app.dustbird.mail.extra.OPEN_OUTBOX"
    }
}
