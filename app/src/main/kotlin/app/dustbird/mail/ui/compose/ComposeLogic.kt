package app.dustbird.mail.ui.compose

import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.send.SendState

object SignatureBlock {
    private const val SEPARATOR = "-- "

    fun block(signature: String): String = "\n\n$SEPARATOR\n${signature.trimEnd()}"

    fun append(body: String, signature: String): String =
        if (signature.isBlank() || body.trimEnd().endsWith(block(signature))) body else body.trimEnd() + block(signature)

    fun swap(body: String, old: String, new: String): String {
        if (old == new) return body
        if (old.isBlank()) return append(body, new)
        val trimmed = body.trimEnd()
        val tail = block(old)
        if (!trimmed.endsWith(tail)) return body
        return append(trimmed.removeSuffix(tail), new)
    }
}

object DraftParts {
    data class Parts(val own: String, val quote: String, val signature: String)

    private val quoteMark = Regex("(?m)^(?:>|-{5,})")

    fun split(body: String, signature: String, anchor: String?): Parts {
        val block = SignatureBlock.block(signature)
        val trimmed = body.trimEnd()
        val signatureStart = if (signature.isNotBlank() && trimmed.endsWith(block)) trimmed.length - block.length else body.length
        val head = body.substring(0, signatureStart)
        val quoteStart = anchor?.takeIf { it.isNotBlank() }?.let { head.indexOf(it) }?.takeIf { it >= 0 } ?: quoteStart(head)
        return Parts(
            own = (if (quoteStart == null) head else head.substring(0, quoteStart)).trim(),
            quote = if (quoteStart == null) "" else head.substring(quoteStart).trim(),
            signature = body.substring(signatureStart),
        )
    }

    fun join(own: String, parts: Parts): String = buildString {
        append(own.trim())
        if (parts.quote.isNotEmpty()) append("\n\n").append(parts.quote)
        append(parts.signature)
    }

    private fun quoteStart(text: String): Int? {
        val start = quoteMark.find(text)?.range?.first ?: return null
        if (text[start] != '>') return start
        val before = text.substring(0, start).trimEnd('\n', '\r')
        val lineStart = before.lastIndexOf('\n') + 1
        return if (before.substring(lineStart).trimEnd().endsWith(":")) lineStart else start
    }
}

object RecipientInput {
    data class Split(val tokens: List<String>, val rest: String)

    fun split(text: String, flush: Boolean): Split {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angled = false
        fun end() {
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(tokens::add)
            current.clear()
        }
        for (c in text) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    current.append(c)
                }
                quoted -> current.append(c)
                c == '<' -> {
                    angled = true
                    current.append(c)
                }
                c == '>' -> {
                    angled = false
                    current.append(c)
                }
                angled -> current.append(c)
                c == ',' || c == ';' || c == '\n' || c == '\r' -> end()
                c.isWhitespace() -> if (current.contains('@')) end() else if (current.isNotEmpty()) current.append(' ')
                else -> current.append(c)
            }
        }
        if (!flush) return Split(tokens, current.toString())
        end()
        return Split(tokens, "")
    }

    fun parse(token: String): EmailAddress {
        val clean = token.filterNot { it.isISOControl() }.trim()
        if ('<' !in clean && clean.any { it.isWhitespace() }) return EmailAddress(clean)
        return Addresses.parseList(clean).singleOrNull() ?: EmailAddress(clean)
    }

    fun isValid(address: EmailAddress): Boolean = Addresses.isValid(address.address)
}

enum class RecipientProblem { NONE, EMPTY, INVALID }

object RecipientCheck {
    fun of(vararg groups: List<EmailAddress>): RecipientProblem {
        val all = groups.flatMap { it }
        return when {
            all.isEmpty() -> RecipientProblem.EMPTY
            all.any { !RecipientInput.isValid(it) } -> RecipientProblem.INVALID
            else -> RecipientProblem.NONE
        }
    }
}

object SubjectText {
    fun clean(text: CharSequence): String = text.toString().replace(Regex("[\\r\\n]+"), " ").filterNot { it == '\u0000' }
}

enum class FailureKind { AUTH, TLS, SENDER, RECIPIENT, SERVER, NETWORK, OTHER;

    companion object {
        fun of(code: String?): FailureKind {
            val kind = code?.substringBefore('|') ?: return OTHER
            return entries.firstOrNull { it.name == kind } ?: OTHER
        }
    }
}

enum class CopyFailure { NO_FOLDER, NOT_BY_SERVER, ERROR;

    companion object {
        fun of(code: String?): CopyFailure = when (code) {
            "SAVE_NO_FOLDER" -> NO_FOLDER
            "SAVE_NOT_BY_SERVER" -> NOT_BY_SERVER
            else -> ERROR
        }
    }
}

sealed interface SendPhase {
    data object Idle : SendPhase
    data class Progress(val storingCopy: Boolean) : SendPhase
    data object Done : SendPhase
    data class CopyFailed(val reason: CopyFailure) : SendPhase
    data class Failed(val kind: FailureKind) : SendPhase
    data object Queued : SendPhase
    data object Unknown : SendPhase

    companion object {
        fun of(state: SendState?, failure: String?, processing: Boolean): SendPhase = when (state) {
            null, SendState.DRAFT, SendState.SENDING -> Progress(false)
            SendState.QUEUED -> if (processing) Progress(false) else Queued
            SendState.ACCEPTED -> if (processing) Progress(true) else Done
            SendState.SAVED -> Done
            SendState.SAVE_FAILED -> CopyFailed(CopyFailure.of(failure))
            SendState.FAILED -> if (processing) Progress(false) else Failed(FailureKind.of(failure))
            SendState.UNKNOWN -> if (processing) Progress(false) else Unknown
        }
    }
}
