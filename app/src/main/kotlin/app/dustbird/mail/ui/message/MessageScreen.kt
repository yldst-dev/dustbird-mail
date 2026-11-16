package app.dustbird.mail.ui.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.dustbird.mail.ui.nav.ComposeArgs

@Composable
fun MessageRoute(messageId: Long, onBack: () -> Unit, onCompose: (ComposeArgs) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("MessageRoute") }
}
