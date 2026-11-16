package app.dustbird.mail.ui.compose

import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.send.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLogicTest {
    @Test
    fun signatureIsAppendedOnceAfterTheBody() {
        val once = SignatureBlock.append("Hello", "Kim\nExample")
        assertEquals("Hello\n\n-- \nKim\nExample", once)
        assertEquals(once, SignatureBlock.append(once, "Kim\nExample"))
        assertEquals("Hello", SignatureBlock.append("Hello", "  \n"))
        assertEquals("\n\n-- \nSig", SignatureBlock.append("", "Sig"))
    }

    @Test
    fun swappingReplacesOnlyTheExactTrailingBlock() {
        val body = SignatureBlock.append("Hello\n\nOld", "Old")
        assertEquals("Hello\n\nOld\n\n-- \nNew", SignatureBlock.swap(body, "Old", "New"))
        assertEquals("Hello\n\nOld", SignatureBlock.swap(body, "Old", ""))
        assertEquals(body, SignatureBlock.swap(body, "Old", "Old"))
    }

    @Test
    fun swappingNeverTouchesEditedOrRemovedSignatures() {
        val edited = "Hello\n\n-- \nOld extra"
        assertEquals(edited, SignatureBlock.swap(edited, "Old", "New"))
        val removed = "Hello"
        assertEquals(removed, SignatureBlock.swap(removed, "Old", "New"))
        val quoted = "Hello\n> -- \n> Old"
        assertEquals(quoted, SignatureBlock.swap(quoted, "Old", "New"))
    }

    @Test
    fun swappingFromNoSignatureAppendsTheNewOne() {
        assertEquals("Hello\n\n-- \nNew", SignatureBlock.swap("Hello", "", "New"))
        assertEquals("Hello", SignatureBlock.swap("Hello", "", ""))
    }

    @Test
    fun swappingKeepsReplyQuoteAndBlankLeadIntact() {
        val seed = "\n\nOn a date, Kim wrote:\n> line\n>"
        val withSignature = SignatureBlock.append(seed, "Old")
        val swapped = SignatureBlock.swap(withSignature, "Old", "New")
        assertTrue(swapped.startsWith(seed))
        assertTrue(swapped.endsWith("\n\n-- \nNew"))
        assertEquals(seed, SignatureBlock.swap(withSignature, "Old", ""))
    }

    @Test
    fun separatorsSplitRecipients() {
        val split = RecipientInput.split("a@example.com, b@example.com;c@example.com\nd@example.com e@example.com f@exa", flush = false)
        assertEquals(listOf("a@example.com", "b@example.com", "c@example.com", "d@example.com", "e@example.com"), split.tokens)
        assertEquals("f@exa", split.rest)
    }

    @Test
    fun flushCommitsTheLastPiece() {
        val split = RecipientInput.split("a@example.com, plain", flush = true)
        assertEquals(listOf("a@example.com", "plain"), split.tokens)
        assertEquals("", split.rest)
    }

    @Test
    fun wordsWithoutAtSignStayInTheInputUntilFlushed() {
        val typing = RecipientInput.split("kim lee ", flush = false)
        assertEquals(emptyList<String>(), typing.tokens)
        assertEquals("kim lee ", typing.rest)
    }

    @Test
    fun displayNamesAndQuotedCommasSurvive() {
        val split = RecipientInput.split("Kim Lee <kim@example.com>, \"Doe, Jane\" <jane@example.com>\n", flush = false)
        assertEquals(listOf("Kim Lee <kim@example.com>", "\"Doe, Jane\" <jane@example.com>"), split.tokens)
        assertEquals(EmailAddress("kim@example.com", "Kim Lee"), RecipientInput.parse(split.tokens[0]))
        assertEquals("jane@example.com", RecipientInput.parse(split.tokens[1]).address)
    }

    @Test
    fun unparsableTokensStayAsInvalidChips() {
        val plain = RecipientInput.parse("plain")
        assertEquals(EmailAddress("plain"), plain)
        assertFalse(RecipientInput.isValid(plain))
        assertFalse(RecipientInput.isValid(RecipientInput.parse("a@b")))
        assertFalse(RecipientInput.isValid(RecipientInput.parse("a b@example.com")))
        assertTrue(RecipientInput.isValid(RecipientInput.parse("user@example.com")))
    }

    @Test
    fun recipientCheckNeedsSomeoneAndOnlyValidAddresses() {
        val ok = EmailAddress("user@example.com")
        val bad = EmailAddress("user")
        assertEquals(RecipientProblem.EMPTY, RecipientCheck.of(emptyList(), emptyList(), emptyList()))
        assertEquals(RecipientProblem.INVALID, RecipientCheck.of(listOf(ok), listOf(bad), emptyList()))
        assertEquals(RecipientProblem.NONE, RecipientCheck.of(emptyList(), emptyList(), listOf(ok)))
    }

    @Test
    fun subjectLosesLineBreaks() {
        assertEquals("a b", SubjectText.clean("a\r\nb"))
    }

    @Test
    fun failureCodesMapToKinds() {
        assertEquals(FailureKind.SENDER, FailureKind.of("SENDER|550"))
        assertEquals(FailureKind.AUTH, FailureKind.of("AUTH"))
        assertEquals(FailureKind.NETWORK, FailureKind.of("NETWORK"))
        assertEquals(FailureKind.OTHER, FailureKind.of(null))
        assertEquals(FailureKind.OTHER, FailureKind.of("SOMETHING"))
        assertEquals(CopyFailure.NO_FOLDER, CopyFailure.of("SAVE_NO_FOLDER"))
        assertEquals(CopyFailure.ERROR, CopyFailure.of(null))
    }

    @Test
    fun phasesNeverReportSuccessBeforeAcceptance() {
        assertEquals(SendPhase.Progress(false), SendPhase.of(SendState.QUEUED, null, processing = true))
        assertEquals(SendPhase.Queued, SendPhase.of(SendState.QUEUED, "NETWORK", processing = false))
        assertEquals(SendPhase.Progress(false), SendPhase.of(SendState.SENDING, null, processing = true))
        assertEquals(SendPhase.Progress(true), SendPhase.of(SendState.ACCEPTED, null, processing = true))
        assertEquals(SendPhase.Done, SendPhase.of(SendState.ACCEPTED, null, processing = false))
        assertEquals(SendPhase.Done, SendPhase.of(SendState.SAVED, null, processing = false))
        assertEquals(SendPhase.CopyFailed(CopyFailure.NOT_BY_SERVER), SendPhase.of(SendState.SAVE_FAILED, "SAVE_NOT_BY_SERVER", processing = false))
        assertEquals(SendPhase.Failed(FailureKind.TLS), SendPhase.of(SendState.FAILED, "TLS", processing = false))
        assertEquals(SendPhase.Progress(false), SendPhase.of(SendState.FAILED, "TLS", processing = true))
        assertEquals(SendPhase.Unknown, SendPhase.of(SendState.UNKNOWN, null, processing = false))
        assertEquals(SendPhase.Progress(false), SendPhase.of(null, null, processing = false))
    }
}
