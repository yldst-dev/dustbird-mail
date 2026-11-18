package app.dustbird.mail.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.FolderRole
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.core.model.TransportSecurity
import app.dustbird.mail.core.send.SendState

@Entity(tableName = "account", indices = [Index("mailboxKey", unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val displayName: String,
    val label: String,
    val username: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: TransportSecurity,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: TransportSecurity,
    val authMethod: AuthMethod,
    val sortOrder: Int,
    val syncMode: SyncMode,
    val notificationsEnabled: Boolean,
    val needsReauth: Boolean,
    val mailboxKey: String,
    val stageImapAuth: StageState = StageState.UNKNOWN,
    val stageSmtpAuth: StageState = StageState.UNKNOWN,
    val stageSend: StageState = StageState.UNKNOWN,
    val stageReceive: StageState = StageState.UNKNOWN,
    val stageSentCopy: StageState = StageState.UNKNOWN,
)

@Entity(
    tableName = "identity",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId"), Index("addressKey", unique = true), Index("labelKey", unique = true)],
)
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val address: String,
    val addressKey: String,
    val displayName: String,
    val signature: String,
    val label: String,
    val labelKey: String,
    val sortOrder: Int,
    val isAccountDefault: Boolean,
    val verification: SendVerification,
)

@Entity(
    tableName = "folder",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "path"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val path: String,
    val delimiter: String?,
    val attributes: String,
    val role: FolderRole?,
    val roleConfirmed: Boolean,
    val uidValidity: Long,
    val highestUid: Long,
    val baselineComplete: Boolean,
    val syncedAt: Long,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["folderId", "uid"], unique = true),
        Index("accountId"),
        Index(value = ["accountId", "messageId"]),
        Index(value = ["folderId", "sortDate"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val uidValidity: Long,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String,
    val fromName: String?,
    val fromAddress: String?,
    val replyTo: String,
    val toAddresses: String,
    val ccAddresses: String,
    val deliveryValues: String,
    val subject: String,
    val sortDate: Long,
    val preview: String,
    val size: Int,
    val hasAttachment: Boolean,
    val seen: Boolean,
    val answered: Boolean,
    val flagged: Boolean,
    val allowRemoteImages: Boolean,
    @ColumnInfo(defaultValue = "0") val listState: Int = ListState.NORMAL,
)

object ListState {
    const val NORMAL = 0
    const val HIDDEN = 1
    const val SEARCH_ONLY = 2
}

@Entity(
    tableName = "message_body",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["messageRowId"], onDelete = ForeignKey.CASCADE)],
)
data class MessageBodyEntity(
    @PrimaryKey val messageRowId: Long,
    val text: String?,
    val html: String?,
    val attachments: String,
)

@Entity(
    tableName = "draft",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId")],
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val identityId: Long,
    val toAddresses: String,
    val ccAddresses: String,
    val bccAddresses: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String,
    val messageId: String,
    val serverCopyAccountId: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "outbox",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId"), Index("messageId", unique = true)],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val identityId: Long,
    val state: SendState,
    val attempts: Int,
    val failure: String?,
    val messageId: String,
    val subject: String,
    val recipientSummary: String,
    val raw: ByteArray,
    val rawWithBcc: ByteArray,
    val envelopeFrom: String,
    val recipients: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rawPath: String? = null,
    @ColumnInfo(defaultValue = "0") val sendAfter: Long = 0,
    val draftId: Long? = null,
)

@Entity(
    tableName = "draft_attachment",
    foreignKeys = [ForeignKey(DraftEntity::class, ["id"], ["draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("draftId")],
)
data class DraftAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: Long,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val path: String,
)

@Entity(
    tableName = "pending_flag",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId"), Index(value = ["folderId", "uid"], unique = true)],
)
data class PendingFlagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val seen: Boolean,
)

@Entity(
    tableName = "notified",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "dedupKey"], unique = true)],
)
data class NotifiedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val dedupKey: String,
    val notifiedAt: Long,
)

@Entity(tableName = "agent_session")
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_item",
    foreignKeys = [ForeignKey(AgentSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class AgentItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kind: String,
    val raw: String?,
    val text: String?,
    val callId: String?,
    val toolName: String?,
    val arguments: String?,
    val output: String?,
    val createdAt: Long,
)

@Entity(tableName = "agent_memory")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "agent_automation")
data class AgentAutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instruction: String,
    val trigger: String,
    val hour: Int,
    val allowChanges: Boolean,
    val enabled: Boolean,
    val lastRunAt: Long,
)

@Entity(tableName = "agent_log", indices = [Index("at")])
data class AgentLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val source: String,
    val tool: String,
    val summary: String,
    val outcome: String,
    val approval: String,
)
