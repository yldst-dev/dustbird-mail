package app.dustbird.mail.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailtoTest {
    @Test
    fun plainAddressAndFields() {
        val data = Mailto.parse("mailto:kim@example.com?subject=Hello%20there&body=Line%201%0D%0ALine%202&cc=lee@example.com&bcc=park@example.com")!!
        assertEquals(listOf("kim@example.com"), data.to.map { it.address })
        assertEquals(listOf("lee@example.com"), data.cc.map { it.address })
        assertEquals(listOf("park@example.com"), data.bcc.map { it.address })
        assertEquals("Hello there", data.subject)
        assertEquals("Line 1\nLine 2", data.body)
    }

    @Test
    fun severalRecipientsKoreanTextAndPlusSign() {
        val data = Mailto.parse("MAILTO:a@example.com,b+tag@example.com?to=c@example.com&Subject=%ED%9A%8C%EC%9D%98+%EC%9D%BC%EC%A0%95")!!
        assertEquals(listOf("a@example.com", "b+tag@example.com", "c@example.com"), data.to.map { it.address })
        assertEquals("회의+일정", data.subject)
    }

    @Test
    fun headerInjectionAndUnknownParametersAreNeutralised() {
        val data = Mailto.parse("mailto:a@example.com?subject=Hi%0D%0ABcc:%20evil@example.com&attach=/data/secret&x-other=1")!!
        assertTrue('\n' !in data.subject && '\r' !in data.subject)
        assertTrue(data.bcc.isEmpty())
        assertEquals(listOf("a@example.com"), data.to.map { it.address })
    }

    @Test
    fun invalidAddressesAreDroppedAndEmptyIsAllowed() {
        val data = Mailto.parse("mailto:not-an-address,ok@example.com?cc=%ZZ")!!
        assertEquals(listOf("ok@example.com"), data.to.map { it.address })
        assertTrue(data.cc.isEmpty())
        assertTrue(Mailto.parse("mailto:")!!.to.isEmpty())
        assertNull(Mailto.parse("https://example.com"))
    }
}
