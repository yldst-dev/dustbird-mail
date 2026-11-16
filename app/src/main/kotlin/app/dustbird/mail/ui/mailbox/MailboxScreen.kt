package app.dustbird.mail.ui.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier


@Composable
fun MailboxRoute(onOpenMessage: (Long) -> Unit, onCompose: () -> Unit, onOpenAccounts: () -> Unit, onOpenSettings: () -> Unit, onOpenOutbox: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("MailboxRoute") }
}
