package app.dustbird.mail.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftPartsTest {
    private val signature = "홍길동 드림"
    private val attribution = "2026. 11. 17. 오후 2:00, 김하늘 님이 보낸 메일:"

    @Test
    fun aNewMailKeepsOnlyItsSignatureOutsideTheOwnText() {
        val body = "안녕하세요.\n견적 부탁드립니다." + SignatureBlock.block(signature)
        val parts = DraftParts.split(body, signature, null)
        assertEquals("안녕하세요.\n견적 부탁드립니다.", parts.own)
        assertEquals("", parts.quote)
        assertEquals("새 글" + SignatureBlock.block(signature), DraftParts.join("새 글", parts))
    }

    @Test
    fun aReplyKeepsTheAttributionQuoteAndSignatureWhenTheOwnTextIsReplaced() {
        val body = "확인했습니다.\n\n$attribution\n> 견적서 보내드립니다.\n>" + SignatureBlock.block(signature)
        val parts = DraftParts.split(body, signature, attribution)
        assertEquals("확인했습니다.", parts.own)
        assertEquals("$attribution\n> 견적서 보내드립니다.\n>", parts.quote)
        assertEquals("감사합니다.\n\n$attribution\n> 견적서 보내드립니다.\n>" + SignatureBlock.block(signature), DraftParts.join(" 감사합니다. ", parts))
    }

    @Test
    fun withoutAKnownAnchorTheQuoteIsFoundByItsMarkerAndAttributionLine() {
        val body = "\n\n$attribution\n> 원문"
        val parts = DraftParts.split(body, "", null)
        assertEquals("", parts.own)
        assertEquals("$attribution\n> 원문", parts.quote)
        val forwarded = DraftParts.split("참고하세요.\n\n---------- 전달된 메일 ----------\n보낸 사람: a@example.com", "", null)
        assertEquals("참고하세요.", forwarded.own)
        assertEquals("---------- 전달된 메일 ----------\n보낸 사람: a@example.com", forwarded.quote)
    }

    @Test
    fun aRemovedSignatureOrQuoteIsNotBroughtBack() {
        val parts = DraftParts.split("직접 고친 글", signature, attribution)
        assertEquals(DraftParts.Parts("직접 고친 글", "", ""), parts)
        assertEquals("새 글", DraftParts.join("새 글", parts))
    }
}
