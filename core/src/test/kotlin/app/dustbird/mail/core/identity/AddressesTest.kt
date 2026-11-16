package app.dustbird.mail.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressesTest {
    @Test
    fun acceptsOrdinaryAddressesAndRejectsMalformedOnes() {
        listOf("user@example.com", "first.last+tag@sub.example.co.kr", "a_b-c@example.org").forEach { assertTrue(Addresses.isValid(it), it) }
        listOf("", "plain", "a@b", "a@@b.com", "@b.com", "a@.com", "a@b..com", ".a@b.com", "a.@b.com", "a b@c.com", "한글@example.com", "a@한글.com", "a@-b.com")
            .forEach { assertFalse(Addresses.isValid(it), it) }
    }

    @Test
    fun rejectsLineBreaksAndControlCharactersThatCouldInjectHeaders() {
        listOf("a@b.com\r\nBcc: x@y.com", "a@b.com\nX: y", "a@b.com\r", "a\u0000@b.com").forEach { assertFalse(Addresses.isValid(it), it) }
        assertFailsWith<IllegalArgumentException> { Addresses.requireSafeHeaderValue("subject\r\nBcc: x@y.com") }
        assertFailsWith<IllegalArgumentException> { Addresses.requireSafeHeaderValue("subject\u0000") }
        assertEquals("fine", Addresses.requireSafeHeaderValue("fine"))
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingSpace() {
        assertTrue(Addresses.same(" User@Example.COM ", "user@example.com"))
        assertFalse(Addresses.same("a@example.com", "b@example.com"))
    }

    @Test
    fun parsesNamesAndEncodedKoreanDisplayNames() {
        val parsed = Addresses.parseList("\"홍 길동\" <hong@example.com>, =?UTF-8?B?7J2066aE?= <name@example.com>, plain@example.com")
        assertEquals(listOf("hong@example.com", "name@example.com", "plain@example.com"), parsed.map { it.address })
        assertEquals("홍 길동", parsed[0].name)
        assertEquals("이름", parsed[1].name)
    }

    @Test
    fun toleratesGarbageHeaders() {
        assertTrue(Addresses.parseList(null).isEmpty())
        assertTrue(Addresses.parseList("<<<>>>").isEmpty())
    }
}
