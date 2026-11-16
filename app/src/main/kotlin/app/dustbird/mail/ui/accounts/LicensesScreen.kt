package app.dustbird.mail.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dustbird.mail.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenseEntry(val summary: Int, val files: List<String>)

private val entries = listOf(
    LicenseEntry(R.string.licenses_pretendard, listOf("pretendard-OFL.txt")),
    LicenseEntry(R.string.licenses_jakarta, listOf("eclipse-ee4j-NOTICE.md", "eclipse-ee4j-LICENSE.md")),
    LicenseEntry(R.string.licenses_jsoup, listOf("jsoup-MIT.txt")),
    LicenseEntry(R.string.licenses_androidx, listOf("apache-2.0.txt")),
    LicenseEntry(R.string.licenses_kotlin, listOf("apache-2.0.txt")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesRoute(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium)
            entries.forEach { entry ->
                HorizontalDivider()
                LicenseItem(entry)
            }
        }
    }
}

@Composable
private fun LicenseItem(entry: LicenseEntry) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Text(stringResource(entry.summary), style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(if (expanded) R.string.licenses_hide_text else R.string.licenses_show_text))
    }
    if (expanded) {
        val text by produceState("") {
            value = withContext(Dispatchers.IO) {
                entry.files.joinToString("\n\n") { file -> context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            }
        }
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
}
