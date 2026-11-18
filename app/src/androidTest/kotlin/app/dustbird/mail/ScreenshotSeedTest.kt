package app.dustbird.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dustbird.mail.core.folder.FolderInfo
import app.dustbird.mail.core.folder.FolderRoles
import app.dustbird.mail.core.folder.RoleResolution
import app.dustbird.mail.core.model.EmailAddress
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.data.repo.AddAccountResult
import app.dustbird.mail.data.repo.DraftContent
import app.dustbird.mail.data.repo.MailboxScope
import app.dustbird.mail.data.repo.NewAccount
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class ScreenshotSeedTest {
    private val imap = ServerEndpoint("10.0.2.2", 3993, TransportSecurity.TLS)
    private val smtp = ServerEndpoint("10.0.2.2", 3465, TransportSecurity.TLS)

    @Test
    fun seed() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seed") == "true")
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MailApplication).container
        prepare("tester@example.test", "tester-pw")
        prepare("friend@example.test", "friend-pw")
        val testerId = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", "업무 메일", "업무", "tester@example.test", "tester@example.test", "테스터", imap, smtp), "tester-pw"),
        ).accountId
        val friendId = assertIs<AddAccountResult.Added>(
            container.accounts.addAccount(NewAccount("custom", "개인 메일", "개인", "friend@example.test", "friend@example.test", "친구", imap, smtp), "friend-pw"),
        ).accountId
        listOf(testerId, friendId).forEach { id ->
            container.sync.syncAccount(id)
            val infos = container.db.folders().forAccount(id).map { FolderInfo(it.path, it.delimiter?.firstOrNull(), it.attributes.split(" ").filter(String::isNotEmpty).toSet()) }
            FolderRoles.resolve(infos, emptyMap()).forEach { (role, resolution) ->
                if (resolution is RoleResolution.NeedsConfirmation) container.sync.confirmRole(id, role, resolution.candidates.first())
            }
        }
        deliver("friend@example.test", "friend-pw", "tester@example.test", "내일 회의 자료 확인 부탁드립니다", text("안녕하세요.\n\n내일 오전 10시 회의 자료를 공유드립니다. 검토 후 의견 주세요.\n\n> 지난번 논의한 일정은 그대로 진행합니다.\n\n감사합니다."))
        deliver("friend@example.test", "friend-pw", "tester@example.test", "11월 소식지 (HTML)", html())
        deliver("friend@example.test", "friend-pw", "tester@example.test", "계약서 초안 첨부", withAttachment())
        deliver("tester@example.test", "tester-pw", "friend@example.test", "주말 약속 정하기", text("이번 주말에 시간 되세요? 토요일 오후가 좋아요."))
        deliver("tester@example.test", "tester-pw", "friend@example.test", "사진 잘 받았어요", text("보내주신 사진 잘 받았습니다. 정말 멋지네요!"))
        container.sync.syncAll()
        val summaries = container.mail.messages(MailboxScope.Unified, 50).first()
        summaries.firstOrNull { it.subject.startsWith("내일") }?.let { container.mail.setSeen(it.id, true) }
        val identity = container.accounts.identities.first().first { it.accountId == testerId }
        val draftId = container.drafts.save(DraftContent(null, identity.id, listOf(EmailAddress("friend@example.test")), emptyList(), emptyList(), "보낸 편지 확인용", "본문입니다.", null, emptyList()))
        container.send.enqueue(draftId)
        container.send.processAccount(testerId)
        container.sync.syncAll()
    }

    private fun prepare(user: String, password: String) {
        val store = Session.getInstance(Properties().apply { put("mail.imap.ssl.enable", "true") }).getStore("imap")
        store.connect("10.0.2.2", 3993, user, password)
        listOf("INBOX", "Sent Messages", "Drafts", "Trash", "Archive", "Junk").forEach { name ->
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            folder.open(Folder.READ_WRITE)
            folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
            folder.close(true)
        }
        store.close()
    }

    private fun session() = Session.getInstance(Properties().apply {
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.auth", "true")
    })

    private fun text(body: String): (MimeMessage) -> Unit = { it.setText(body, "UTF-8") }

    private fun html(): (MimeMessage) -> Unit = {
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText("11월 소식지입니다. HTML 버전을 확인해 주세요.", "UTF-8") })
        alternative.addBodyPart(
            MimeBodyPart().apply {
                setContent(
                    "<html><body><h2>11월 소식지</h2><p>이번 달 <b>주요 소식</b>을 전해 드립니다.</p>" +
                        "<script>alert(1)</script><img src=\"https://tracker.example/pixel.gif\">" +
                        "<ul><li>새 사무실 이전 안내</li><li>연말 행사 일정</li></ul>" +
                        "<p><a href=\"https://example.com/news\">전체 보기</a></p></body></html>",
                    "text/html; charset=UTF-8",
                )
            },
        )
        it.setContent(alternative)
    }

    private fun withAttachment(): (MimeMessage) -> Unit = {
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(MimeBodyPart().apply { setText("계약서 초안을 첨부합니다. 확인 후 회신 부탁드립니다.", "UTF-8") })
        mixed.addBodyPart(MimeBodyPart().apply {
            setContent("secret contract text", "text/plain")
            fileName = "계약서_초안.txt"
            disposition = "attachment"
        })
        it.setContent(mixed)
    }

    private fun deliver(from: String, fromPassword: String, to: String, subject: String, fill: (MimeMessage) -> Unit) {
        val session = session()
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from, if (from.startsWith("friend")) "친구" else "테스터", "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            setSubject(subject, "UTF-8")
            fill(this)
            saveChanges()
        }
        session.getTransport("smtp").apply {
            connect("10.0.2.2", 3465, from, fromPassword)
            sendMessage(message, message.allRecipients)
            close()
        }
    }
}
