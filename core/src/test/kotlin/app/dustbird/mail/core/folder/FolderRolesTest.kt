package app.dustbird.mail.core.folder

import app.dustbird.mail.core.model.FolderRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FolderRolesTest {
    private fun folder(path: String, delimiter: Char? = '/', vararg attrs: String) = FolderInfo(path, delimiter, attrs.toSet())

    private val gmailLike = listOf(
        folder("INBOX"),
        folder("[Gmail]", '/', "\\Noselect", "\\HasChildren"),
        folder("[Gmail]/Sent Mail", '/', "\\Sent"),
        folder("[Gmail]/Drafts", '/', "\\Drafts"),
        folder("[Gmail]/Spam", '/', "\\Junk"),
        folder("[Gmail]/Trash", '/', "\\Trash"),
        folder("[Gmail]/All Mail", '/', "\\All"),
    )

    @Test
    fun specialUseAttributesDecideRolesEvenWhenNamesAreUnfamiliar() {
        val result = FolderRoles.resolve(gmailLike, emptyMap())
        assertEquals(RoleResolution.Assigned("INBOX", RoleSource.NAME_RULE), result.getValue(FolderRole.INBOX))
        assertEquals(RoleResolution.Assigned("[Gmail]/Sent Mail", RoleSource.ATTRIBUTE), result.getValue(FolderRole.SENT))
        assertEquals(RoleResolution.Assigned("[Gmail]/Spam", RoleSource.ATTRIBUTE), result.getValue(FolderRole.SPAM))
        assertEquals(RoleResolution.Assigned("[Gmail]/All Mail", RoleSource.ATTRIBUTE), result.getValue(FolderRole.ALL))
        assertEquals(RoleResolution.Missing, result.getValue(FolderRole.ARCHIVE))
    }

    @Test
    fun foldersThatCannotBeSelectedAreNeverAssigned() {
        val result = FolderRoles.resolve(gmailLike + folder("Sent", '/', "\\Noselect"), emptyMap())
        assertFalse(result.values.any { it is RoleResolution.Assigned && it.path == "[Gmail]" })
        assertIs<RoleResolution.Assigned>(result.getValue(FolderRole.SENT))
    }

    @Test
    fun koreanNamesWithoutAttributesOnlyProduceCandidates() {
        val folders = listOf(folder("INBOX"), folder("보낸 편지함"), folder("임시보관함"), folder("스팸 메일함"), folder("휴지통"))
        val result = FolderRoles.resolve(folders, emptyMap())
        assertEquals(RoleResolution.NeedsConfirmation(listOf("보낸 편지함")), result.getValue(FolderRole.SENT))
        assertEquals(RoleResolution.NeedsConfirmation(listOf("임시보관함")), result.getValue(FolderRole.DRAFTS))
        assertEquals(RoleResolution.NeedsConfirmation(listOf("스팸 메일함")), result.getValue(FolderRole.SPAM))
        assertEquals(RoleResolution.NeedsConfirmation(listOf("휴지통")), result.getValue(FolderRole.TRASH))
    }

    @Test
    fun englishNamesWithoutAttributesAreSuggestionsToo() {
        val folders = listOf(folder("INBOX"), folder("Sent Messages"), folder("Drafts"), folder("Junk"), folder("Deleted Messages"), folder("Archive"))
        val result = FolderRoles.resolve(folders, emptyMap())
        FolderRole.entries.filter { it != FolderRole.INBOX && it != FolderRole.ALL }.forEach {
            assertIs<RoleResolution.NeedsConfirmation>(result.getValue(it), it.name)
        }
    }

    @Test
    fun userConfirmationWinsAndStaleConfirmationIsIgnored() {
        val folders = listOf(folder("INBOX"), folder("Sent Messages"), folder("My Sent"))
        val confirmed = mapOf(FolderRole.SENT to "My Sent", FolderRole.DRAFTS to "Gone")
        val result = FolderRoles.resolve(folders, confirmed)
        assertEquals(RoleResolution.Assigned("My Sent", RoleSource.USER), result.getValue(FolderRole.SENT))
        assertEquals(RoleResolution.Missing, result.getValue(FolderRole.DRAFTS))
    }

    @Test
    fun nestedNamesCompareOnTheLastSegment() {
        val folders = listOf(folder("INBOX", '.'), folder("INBOX.Sent", '.'))
        assertEquals(RoleResolution.NeedsConfirmation(listOf("INBOX.Sent")), FolderRoles.resolve(folders, emptyMap()).getValue(FolderRole.SENT))
    }

    @Test
    fun attributesAreCaseInsensitive() {
        assertEquals(FolderRole.SENT, FolderRoles.roleFromAttributes(setOf("\\SENT")))
        assertEquals(null, FolderRoles.roleFromAttributes(setOf("\\HasChildren")))
    }
}
