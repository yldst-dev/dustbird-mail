package app.dustbird.mail.core.folder

import app.dustbird.mail.core.model.FolderRole

data class FolderInfo(
    val path: String,
    val delimiter: Char?,
    val attributes: Set<String>,
) {
    val selectable: Boolean get() = attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }
}

enum class RoleSource { ATTRIBUTE, NAME_RULE, USER }

sealed interface RoleResolution {
    data class Assigned(val path: String, val source: RoleSource) : RoleResolution
    data class NeedsConfirmation(val candidates: List<String>) : RoleResolution
    data object Missing : RoleResolution
}

object FolderRoles {
    private val attributeRoles = mapOf(
        "\\sent" to FolderRole.SENT,
        "\\drafts" to FolderRole.DRAFTS,
        "\\junk" to FolderRole.SPAM,
        "\\trash" to FolderRole.TRASH,
        "\\archive" to FolderRole.ARCHIVE,
        "\\all" to FolderRole.ALL,
    )

    private val candidateNames = mapOf(
        FolderRole.SENT to setOf("sent", "sentitems", "sentmessages", "sentmail", "보낸편지함", "보낸메일함"),
        FolderRole.DRAFTS to setOf("drafts", "draft", "임시보관함", "임시저장", "임시저장함"),
        FolderRole.SPAM to setOf("junk", "junke-mail", "junkemail", "spam", "bulkmail", "스팸메일함", "스팸편지함", "스팸함"),
        FolderRole.TRASH to setOf("trash", "deleteditems", "deletedmessages", "bin", "휴지통", "지운편지함", "삭제된편지함"),
        FolderRole.ARCHIVE to setOf("archive", "archives", "보관함", "보관편지함"),
    )

    fun roleFromAttributes(attributes: Set<String>): FolderRole? =
        attributes.firstNotNullOfOrNull { attributeRoles[it.lowercase()] }

    fun isInbox(path: String): Boolean = path.equals("INBOX", ignoreCase = true)

    fun resolve(folders: List<FolderInfo>, confirmed: Map<FolderRole, String>): Map<FolderRole, RoleResolution> {
        val selectable = folders.filter { it.selectable }
        val paths = selectable.map { it.path }.toSet()
        return FolderRole.entries.associateWith { role -> resolveRole(role, selectable, paths, confirmed) }
    }

    private fun resolveRole(
        role: FolderRole,
        folders: List<FolderInfo>,
        paths: Set<String>,
        confirmed: Map<FolderRole, String>,
    ): RoleResolution {
        confirmed[role]?.takeIf { it in paths }?.let { return RoleResolution.Assigned(it, RoleSource.USER) }
        if (role == FolderRole.INBOX) {
            return folders.firstOrNull { isInbox(it.path) }
                ?.let { RoleResolution.Assigned(it.path, RoleSource.NAME_RULE) }
                ?: RoleResolution.Missing
        }
        folders.firstOrNull { roleFromAttributes(it.attributes) == role }
            ?.let { return RoleResolution.Assigned(it.path, RoleSource.ATTRIBUTE) }
        val names = candidateNames[role].orEmpty()
        val candidates = folders.filter { normalizedLeaf(it) in names }.map { it.path }
        return if (candidates.isEmpty()) RoleResolution.Missing else RoleResolution.NeedsConfirmation(candidates)
    }

    private fun normalizedLeaf(folder: FolderInfo): String {
        val leaf = folder.delimiter?.let { folder.path.substringAfterLast(it) } ?: folder.path
        return leaf.lowercase().filterNot { it.isWhitespace() }
    }
}
