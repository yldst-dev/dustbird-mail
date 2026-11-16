package app.dustbird.mail.core.identity

import app.dustbird.mail.core.account
import app.dustbird.mail.core.identity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    private val all = listOf(
        identity(1, 10, "me@example.com", "me", isDefault = true),
        identity(2, 10, "biz@example.com", "biz"),
        identity(3, 10, "third@example.com", "third"),
        identity(4, 20, "other@example.org", "other", isDefault = true),
    )

    @Test
    fun acceptsFreshAddressAndLabel() {
        assertNull(IdentityRules.validate(all, IdentityDraft(10, "new@example.com", "new")))
    }

    @Test
    fun rejectsInvalidDuplicateAddressesAcrossAccountsIgnoringCase() {
        assertEquals(Rejection.INVALID_ADDRESS, IdentityRules.validate(all, IdentityDraft(10, "nope", "x")))
        assertEquals(Rejection.DUPLICATE_ADDRESS, IdentityRules.validate(all, IdentityDraft(10, "ME@Example.com", "x")))
        assertEquals(Rejection.DUPLICATE_ADDRESS, IdentityRules.validate(all, IdentityDraft(20, "biz@example.com", "x")))
    }

    @Test
    fun labelsAreUniqueAcrossTheWholeAppAndNotBlank() {
        assertEquals(Rejection.DUPLICATE_LABEL, IdentityRules.validate(all, IdentityDraft(20, "x@example.org", " BIZ ")))
        assertEquals(Rejection.BLANK_LABEL, IdentityRules.validate(all, IdentityDraft(20, "x@example.org", "  ")))
    }

    @Test
    fun editingAnIdentityMayKeepItsOwnAddressAndLabel() {
        assertNull(IdentityRules.validate(all, IdentityDraft(10, "me@example.com", "me"), editingId = 1))
        assertEquals(Rejection.DUPLICATE_LABEL, IdentityRules.validate(all, IdentityDraft(10, "me@example.com", "biz"), editingId = 1))
    }

    @Test
    fun defaultLabelsNeverCollide() {
        assertEquals("fresh", IdentityRules.defaultLabel(all, "fresh@example.com"))
        assertEquals("me2", IdentityRules.defaultLabel(all, "me@elsewhere.net"))
        val more = all + identity(5, 10, "me2@example.com", "me2")
        assertEquals("me3", IdentityRules.defaultLabel(more, "me@elsewhere.net"))
    }

    @Test
    fun lastIdentityOfAnAccountCannotBeRemoved() {
        val rejected = IdentityRules.planRemoval(all.filter { it.accountId == 20L }, 4)
        assertEquals(Rejection.LAST_IDENTITY, assertIs<RuleResult.Rejected>(rejected).reason)
        assertEquals(Rejection.UNKNOWN_IDENTITY, assertIs<RuleResult.Rejected>(IdentityRules.planRemoval(all, 99)).reason)
    }

    @Test
    fun removingTheDefaultPromotesTheFirstRemainingAndMovesDraftsThere() {
        val account = all.filter { it.accountId == 10L }
        val plan = assertIs<RuleResult.Ok<RemovalPlan>>(IdentityRules.planRemoval(account, 1)).value
        assertEquals(2L, plan.fallbackIdentityId)
        assertEquals(2L, plan.newDefaultId)
    }

    @Test
    fun removingANonDefaultKeepsTheDefaultAndMovesDraftsToIt() {
        val account = all.filter { it.accountId == 10L }
        val plan = assertIs<RuleResult.Ok<RemovalPlan>>(IdentityRules.planRemoval(account, 3)).value
        assertEquals(1L, plan.fallbackIdentityId)
        assertNull(plan.newDefaultId)
    }

    @Test
    fun reorderAndDefaultChangeAreConsistent() {
        val account = all.filter { it.accountId == 10L }
        val reordered = IdentityRules.reorder(account, listOf(3, 1, 2))
        assertEquals(listOf(3L, 1L, 2L), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2), reordered.map { it.sortOrder })
        val changed = IdentityRules.withDefault(account, 3)
        assertEquals(listOf(3L), changed.filter { it.isAccountDefault }.map { it.id })
    }

    @Test
    fun sameMailboxCannotBeRegisteredTwiceButSameProviderDifferentUserCan() {
        val accounts = listOf(account(1, "imap.gmail.com", "a@gmail.com"), account(2, "imap.gmail.com", "b@gmail.com"))
        assertTrue(AccountRules.isDuplicateMailbox(accounts, "IMAP.GMAIL.COM", " A@Gmail.com "))
        assertFalse(AccountRules.isDuplicateMailbox(accounts, "imap.gmail.com", "c@gmail.com"))
        assertFalse(AccountRules.isDuplicateMailbox(accounts, "imap.gmail.com", "a@gmail.com", editingId = 1))
        assertFalse(AccountRules.isDuplicateMailbox(accounts, "imap.naver.com", "a@gmail.com"))
    }
}
