package app.dustbird.mail.data.repo

import androidx.room.withTransaction
import app.dustbird.mail.core.identity.AccountRules
import app.dustbird.mail.core.identity.Addresses
import app.dustbird.mail.core.identity.IdentityDraft
import app.dustbird.mail.core.identity.IdentityRules
import app.dustbird.mail.core.identity.Rejection
import app.dustbird.mail.core.identity.RemovalPlan
import app.dustbird.mail.core.identity.RuleResult
import app.dustbird.mail.core.model.AuthMethod
import app.dustbird.mail.core.model.ConnectionStages
import app.dustbird.mail.core.model.Credentials
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.core.model.MailIdentity
import app.dustbird.mail.core.model.SendVerification
import app.dustbird.mail.core.model.ServerEndpoint
import app.dustbird.mail.core.model.StageState
import app.dustbird.mail.core.model.SyncMode
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.data.db.AccountEntity
import app.dustbird.mail.data.db.IdentityEntity
import app.dustbird.mail.data.db.MailDatabase
import app.dustbird.mail.data.secure.CredentialStore
import app.dustbird.mail.data.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class NewAccount(
    val providerId: String,
    val displayName: String,
    val label: String,
    val username: String,
    val loginAddress: String,
    val identityDisplayName: String,
    val imap: ServerEndpoint,
    val smtp: ServerEndpoint,
)

data class IdentityInput(val address: String, val displayName: String, val signature: String, val label: String)

data class AccountWithIdentities(val account: MailAccount, val identities: List<MailIdentity>)

data class IdentityRemoval(val fallbackIdentityId: Long, val movedDrafts: Int)

sealed interface AddAccountResult {
    data class Added(val accountId: Long) : AddAccountResult
    data class Rejected(val reason: Rejection) : AddAccountResult
}

sealed interface RemoveAccountResult {
    data object Removed : RemoveAccountResult
    data class Blocked(val unsettledMessages: Int, val drafts: Int) : RemoveAccountResult
}

sealed interface IdentityResult {
    data class Saved(val identityId: Long) : IdentityResult
    data class Rejected(val reason: Rejection) : IdentityResult
}

class AccountRepository(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val onRemoved: (Long) -> Unit,
) {
    val accounts: Flow<List<MailAccount>> = db.accounts().observeAll().map { list -> list.map { it.toModel() } }
    val identities: Flow<List<MailIdentity>> = db.identities().observeAll().map { list -> list.map { it.toModel() } }
    val accountsWithIdentities: Flow<List<AccountWithIdentities>> = combine(accounts, identities) { accountList, identityList ->
        accountList.map { account -> AccountWithIdentities(account, identityList.filter { it.accountId == account.id }) }
    }
    val appDefaultIdentityId: Flow<Long?> = settings.appDefaultIdentityId

    suspend fun account(id: Long): MailAccount? = db.accounts().get(id)?.toModel()

    suspend fun credentialsFor(accountId: Long): Credentials? {
        val account = db.accounts().get(accountId) ?: return null
        val secret = credentials.get(accountId) ?: return null
        return Credentials(account.username, secret)
    }

    suspend fun addAccount(new: NewAccount, secret: String, stages: ConnectionStages = ConnectionStages()): AddAccountResult {
        if (!Addresses.isValid(new.loginAddress)) return AddAccountResult.Rejected(Rejection.INVALID_ADDRESS)
        val existing = db.accounts().getAll().map { it.toModel() }
        if (AccountRules.isDuplicateMailbox(existing, new.imap.host, new.username)) return AddAccountResult.Rejected(Rejection.DUPLICATE_MAILBOX)
        val allIdentities = db.identities().getAll().map { it.toModel() }
        val label = IdentityRules.defaultLabel(allIdentities, new.loginAddress)
        IdentityRules.validate(allIdentities, IdentityDraft(0, new.loginAddress, label))?.let { return AddAccountResult.Rejected(it) }
        var firstIdentityId = 0L
        val accountId = db.withTransaction {
            val id = db.accounts().insert(
                AccountEntity(
                    providerId = new.providerId,
                    displayName = new.displayName.trim(),
                    label = new.label.trim(),
                    username = new.username.trim(),
                    imapHost = new.imap.host.trim(),
                    imapPort = new.imap.port,
                    imapSecurity = new.imap.security,
                    smtpHost = new.smtp.host.trim(),
                    smtpPort = new.smtp.port,
                    smtpSecurity = new.smtp.security,
                    authMethod = AuthMethod.APP_PASSWORD,
                    sortOrder = db.accounts().nextSortOrder(),
                    syncMode = SyncMode.PERIODIC,
                    notificationsEnabled = true,
                    needsReauth = false,
                    mailboxKey = AccountRules.mailboxKey(new.imap.host, new.username),
                    stageImapAuth = stages.imapAuth,
                    stageSmtpAuth = stages.smtpAuth,
                    stageSend = stages.send,
                    stageReceive = stages.receive,
                    stageSentCopy = stages.sentCopy,
                ),
            )
            firstIdentityId = db.identities().insert(
                IdentityEntity(
                    accountId = id,
                    address = new.loginAddress.trim(),
                    addressKey = Addresses.normalize(new.loginAddress),
                    displayName = new.identityDisplayName.trim(),
                    signature = "",
                    label = label,
                    labelKey = label.trim().lowercase(),
                    sortOrder = 0,
                    isAccountDefault = true,
                    verification = SendVerification.UNVERIFIED,
                ),
            )
            id
        }
        try {
            credentials.put(accountId, secret)
        } catch (error: Exception) {
            db.accounts().delete(accountId)
            throw error
        }
        if (settings.appDefaultIdentityId.first() == null) settings.setAppDefaultIdentity(firstIdentityId)
        return AddAccountResult.Added(accountId)
    }

    suspend fun updateAccount(
        accountId: Long,
        displayName: String,
        label: String,
        username: String,
        imap: ServerEndpoint,
        smtp: ServerEndpoint,
        syncMode: SyncMode,
        notificationsEnabled: Boolean,
    ): Rejection? {
        val current = db.accounts().get(accountId) ?: return Rejection.UNKNOWN_IDENTITY
        val others = db.accounts().getAll().filter { it.id != accountId }.map { it.toModel() }
        if (AccountRules.isDuplicateMailbox(others, imap.host, username)) return Rejection.DUPLICATE_MAILBOX
        db.accounts().update(
            current.copy(
                displayName = displayName.trim(),
                label = label.trim(),
                username = username.trim(),
                imapHost = imap.host.trim(),
                imapPort = imap.port,
                imapSecurity = imap.security,
                smtpHost = smtp.host.trim(),
                smtpPort = smtp.port,
                smtpSecurity = smtp.security,
                syncMode = syncMode,
                notificationsEnabled = notificationsEnabled,
                mailboxKey = AccountRules.mailboxKey(imap.host, username),
            ),
        )
        return null
    }

    suspend fun reauthenticate(accountId: Long, secret: String, stages: ConnectionStages) {
        credentials.put(accountId, secret)
        db.accounts().get(accountId)?.let {
            db.accounts().update(
                it.copy(
                    needsReauth = false,
                    stageImapAuth = stages.imapAuth,
                    stageSmtpAuth = stages.smtpAuth,
                ),
            )
        }
    }

    suspend fun markNeedsReauth(accountId: Long) = db.accounts().setNeedsReauth(accountId, true)

    suspend fun updateStages(accountId: Long, transform: (ConnectionStages) -> ConnectionStages) {
        val current = db.accounts().get(accountId) ?: return
        val next = transform(current.toModel().stages)
        db.accounts().update(
            current.copy(
                stageImapAuth = next.imapAuth,
                stageSmtpAuth = next.smtpAuth,
                stageSend = next.send,
                stageReceive = next.receive,
                stageSentCopy = next.sentCopy,
            ),
        )
    }

    suspend fun removeAccount(accountId: Long, force: Boolean): RemoveAccountResult {
        val unsettled = db.outbox().unsettledCount(accountId)
        val drafts = db.drafts().countForAccount(accountId)
        if (!force && (unsettled > 0 || drafts > 0)) return RemoveAccountResult.Blocked(unsettled, drafts)
        val ownIdentityIds = db.identities().forAccount(accountId).map { it.id }
        db.accounts().delete(accountId)
        credentials.delete(accountId)
        if (settings.appDefaultIdentityId.first() in ownIdentityIds) {
            settings.setAppDefaultIdentity(db.identities().getAll().firstOrNull { it.isAccountDefault }?.id)
        }
        onRemoved(accountId)
        return RemoveAccountResult.Removed
    }

    suspend fun reorderAccounts(orderedIds: List<Long>) = db.withTransaction {
        orderedIds.forEachIndexed { index, id -> db.accounts().setSortOrder(id, index) }
    }

    suspend fun addIdentity(accountId: Long, input: IdentityInput): IdentityResult {
        val all = db.identities().getAll().map { it.toModel() }
        IdentityRules.validate(all, IdentityDraft(accountId, input.address, input.label))?.let { return IdentityResult.Rejected(it) }
        val own = all.filter { it.accountId == accountId }
        val id = db.identities().insert(
            IdentityEntity(
                accountId = accountId,
                address = input.address.trim(),
                addressKey = Addresses.normalize(input.address),
                displayName = input.displayName.trim(),
                signature = input.signature,
                label = input.label.trim(),
                labelKey = input.label.trim().lowercase(),
                sortOrder = (own.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                isAccountDefault = own.isEmpty(),
                verification = SendVerification.UNVERIFIED,
            ),
        )
        return IdentityResult.Saved(id)
    }

    suspend fun updateIdentity(identityId: Long, input: IdentityInput): IdentityResult {
        val current = db.identities().get(identityId) ?: return IdentityResult.Rejected(Rejection.UNKNOWN_IDENTITY)
        val all = db.identities().getAll().map { it.toModel() }
        IdentityRules.validate(all, IdentityDraft(current.accountId, input.address, input.label), editingId = identityId)
            ?.let { return IdentityResult.Rejected(it) }
        val addressChanged = !Addresses.same(current.address, input.address)
        db.identities().update(
            current.copy(
                address = input.address.trim(),
                addressKey = Addresses.normalize(input.address),
                displayName = input.displayName.trim(),
                signature = input.signature,
                label = input.label.trim(),
                labelKey = input.label.trim().lowercase(),
                verification = if (addressChanged) SendVerification.UNVERIFIED else current.verification,
            ),
        )
        return IdentityResult.Saved(identityId)
    }

    suspend fun removeIdentity(identityId: Long): RuleResult<IdentityRemoval> {
        val target = db.identities().get(identityId) ?: return RuleResult.Rejected(Rejection.UNKNOWN_IDENTITY)
        val account = db.identities().forAccount(target.accountId).map { it.toModel() }
        val plan: RemovalPlan = when (val result = IdentityRules.planRemoval(account, identityId)) {
            is RuleResult.Rejected -> return result
            is RuleResult.Ok -> result.value
        }
        val moved = db.withTransaction {
            val draftsMoved = db.drafts().countForIdentity(identityId)
            db.drafts().moveIdentity(identityId, plan.fallbackIdentityId, target.accountId)
            db.identities().delete(identityId)
            plan.newDefaultId?.let { db.identities().setAccountDefault(target.accountId, it) }
            draftsMoved
        }
        if (settings.appDefaultIdentityId.first() == identityId) settings.setAppDefaultIdentity(plan.fallbackIdentityId)
        return RuleResult.Ok(IdentityRemoval(plan.fallbackIdentityId, moved))
    }

    suspend fun reorderIdentities(accountId: Long, orderedIds: List<Long>) = db.withTransaction {
        val own = db.identities().forAccount(accountId).map { it.toModel() }
        IdentityRules.reorder(own, orderedIds).forEach { db.identities().setSortOrder(it.id, it.sortOrder) }
    }

    suspend fun setAccountDefaultIdentity(accountId: Long, identityId: Long) = db.identities().setAccountDefault(accountId, identityId)

    suspend fun setAppDefaultIdentity(identityId: Long?) = settings.setAppDefaultIdentity(identityId)

    suspend fun setVerification(identityId: Long, state: SendVerification) = db.identities().setVerification(identityId, state)

    suspend fun markStage(accountId: Long, transform: ConnectionStages.() -> ConnectionStages) = updateStages(accountId) { it.transform() }

    companion object {
        fun stageOk(ok: Boolean): StageState = if (ok) StageState.OK else StageState.FAILED
    }
}
