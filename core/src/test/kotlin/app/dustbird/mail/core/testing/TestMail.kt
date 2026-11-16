package app.dustbird.mail.core.testing

import app.dustbird.mail.core.mime.ComposedMessage
import app.dustbird.mail.core.mime.MimeComposer
import app.dustbird.mail.core.mime.OutgoingMessage
import app.dustbird.mail.core.model.EmailAddress
import java.time.Instant

object TestMail {
    fun compose(
        id: String,
        from: String = "bob@example.test",
        to: String = "alice@example.test",
        subject: String = "제목 $id",
        bcc: List<EmailAddress> = emptyList(),
    ): ComposedMessage = MimeComposer.compose(
        OutgoingMessage(
            from = EmailAddress(from, "발신자"),
            to = listOf(EmailAddress(to)),
            cc = emptyList(),
            bcc = bcc,
            subject = subject,
            text = "본문 $id",
            messageId = id,
            inReplyTo = null,
            references = emptyList(),
            sentAt = Instant.parse("2026-11-16T01:02:03Z"),
        ),
    )
}
