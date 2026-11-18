package app.dustbird.mail.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

private const val MASK = "•"

fun Modifier.hiddenSecret(length: Int, hidden: Boolean = true): Modifier =
    if (!hidden) {
        this
    } else {
        val masked = AnnotatedString(MASK.repeat(length))
        semantics {
            editableText = masked
            inputText = masked
        }
    }
