package app.dustbird.mail.notify

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dustbird.mail.MainActivity
import app.dustbird.mail.R
import app.dustbird.mail.core.model.MailAccount
import app.dustbird.mail.data.SettingsRepository
import app.dustbird.mail.sync.MailNotifier
import app.dustbird.mail.sync.NewMail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AndroidNotifier(
    private val context: Context,
    private val settings: SettingsRepository,
) : MailNotifier {
    private val compat = NotificationManagerCompat.from(context)
    private val system = context.getSystemService(NotificationManager::class.java)

    override fun notifyNew(account: MailAccount, mails: List<NewMail>) {
        if (!compat.areNotificationsEnabled()) return
        ensureAccountChannel(account)
        val hide = runBlocking { settings.hideLockScreenContent.first() }
        val accountName = account.label.ifBlank { account.displayName }
        val publicVersion = NotificationCompat.Builder(context, channelId(account.id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_public_title))
            .setContentText(context.getString(R.string.notify_public_text, accountName))
            .build()
        mails.forEach { mail ->
            val title = mail.sender.ifBlank { context.getString(R.string.notify_unknown_sender) }
            val subject = mail.subject.ifBlank { context.getString(R.string.notify_no_subject) }
            val notification = NotificationCompat.Builder(context, channelId(account.id))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(subject, mail.preview).filter { it.isNotBlank() }.joinToString("\n")))
                .setSubText(mail.identityLabel?.let { context.getString(R.string.notify_subtext, accountName, it) } ?: accountName)
                .setContentIntent(openMessage(mail.messageRowId))
                .addAction(0, context.getString(R.string.notify_action_read), action(MailActionReceiver.ACTION_READ, account.id, mail.messageRowId, 0))
                .addAction(0, context.getString(R.string.notify_action_archive), action(MailActionReceiver.ACTION_ARCHIVE, account.id, mail.messageRowId, 1))
                .addAction(0, context.getString(R.string.notify_action_delete), action(MailActionReceiver.ACTION_DELETE, account.id, mail.messageRowId, 2))
                .setAutoCancel(true)
                .setGroup(groupKey(account.id))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
                .setPublicVersion(publicVersion)
                .build()
            post(tag(account.id), mail.messageRowId.toInt(), notification)
        }
        val shown = system.activeNotifications
            .filter { it.tag == tag(account.id) && it.id != summaryId(account.id) && it.id != reauthId(account.id) }
            .map { it.id } + mails.map { it.messageRowId.toInt() }
        val summary = NotificationCompat.Builder(context, channelId(account.id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_summary_title, accountName))
            .setContentText(context.getString(R.string.notify_summary_text, shown.toSet().size))
            .setGroup(groupKey(account.id))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        post(tag(account.id), summaryId(account.id), summary)
    }

    override fun reauthRequired(account: MailAccount) {
        if (!compat.areNotificationsEnabled()) return
        ensureReauthChannel()
        val accountName = account.label.ifBlank { account.displayName }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ACCOUNT_ID, account.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, reauthId(account.id), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, REAUTH_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_reauth_title, accountName))
            .setContentText(context.getString(R.string.notify_reauth_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_reauth_text)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        post(tag(account.id), reauthId(account.id), notification)
    }

    override fun reauthResolved(accountId: Long) = system.cancel(tag(accountId), reauthId(accountId))

    override fun sendFailed(outboxId: Long, subject: String, watched: Boolean) {
        if (!compat.areNotificationsEnabled() || (watched && appVisible())) return
        system.createNotificationChannel(NotificationChannel(SEND_CHANNEL, context.getString(R.string.notify_channel_send), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_OUTBOX, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, SEND_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = subject.ifBlank { context.getString(R.string.notify_no_subject) }
        val notification = NotificationCompat.Builder(context, SEND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_send_failed_title))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_send_failed_text, title)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        post(SEND_TAG, outboxId.toInt(), notification)
    }

    override suspend fun sweep(accountId: Long, stillUnread: suspend (List<Long>) -> Set<Long>) {
        val shown = system.activeNotifications
            .filter { it.tag == tag(accountId) && it.id != summaryId(accountId) && it.id != reauthId(accountId) }
            .map { it.id.toLong() }
        if (shown.isEmpty()) return
        val keep = stillUnread(shown)
        shown.filter { it !in keep }.forEach { cancelMessage(context, accountId, it) }
    }

    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun agentReport(title: String, text: String, sessionId: Long) {
        if (!compat.areNotificationsEnabled()) return
        system.createNotificationChannel(NotificationChannel(AGENT_CHANNEL, context.getString(R.string.agent_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AGENT_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, AGENT_REQUEST + sessionId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        post(AGENT_TAG, sessionId.toInt(), notification)
    }

    override fun cancel(accountId: Long) {
        system.activeNotifications.filter { it.tag == tag(accountId) }.forEach { system.cancel(it.tag, it.id) }
    }

    override fun prune(existingAccountIds: Set<Long>) {
        system.notificationChannels
            .filter { it.id.startsWith(ACCOUNT_CHANNEL_PREFIX) && it.id.removePrefix(ACCOUNT_CHANNEL_PREFIX).toLongOrNull() !in existingAccountIds }
            .forEach { system.deleteNotificationChannel(it.id) }
    }

    private fun post(tag: String, id: Int, notification: android.app.Notification) {
        try {
            compat.notify(tag, id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureAccountChannel(account: MailAccount) {
        system.createNotificationChannelGroup(NotificationChannelGroup(ACCOUNT_GROUP, context.getString(R.string.notify_group_accounts)))
        val channel = NotificationChannel(channelId(account.id), account.displayName.ifBlank { account.label }, NotificationManager.IMPORTANCE_DEFAULT)
        channel.group = ACCOUNT_GROUP
        system.createNotificationChannel(channel)
    }

    private fun ensureReauthChannel() {
        system.createNotificationChannel(NotificationChannel(REAUTH_CHANNEL, context.getString(R.string.notify_channel_reauth), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openMessage(messageRowId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MESSAGE_ID, messageRowId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, messageRowId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(name: String, accountId: Long, messageRowId: Long, slot: Int): PendingIntent {
        val intent = Intent(context, MailActionReceiver::class.java)
            .setAction(name)
            .putExtra(MailActionReceiver.EXTRA_ACCOUNT, accountId)
            .putExtra(MailActionReceiver.EXTRA_MESSAGE, messageRowId)
        return PendingIntent.getBroadcast(context, messageRowId.toInt() * 3 + slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun channelId(accountId: Long) = "$ACCOUNT_CHANNEL_PREFIX$accountId"

    private fun groupKey(accountId: Long) = "account_$accountId"

    companion object {
        private fun tag(accountId: Long) = "account_$accountId"

        private fun summaryId(accountId: Long) = -(accountId.toInt() + 1)

        private fun reauthId(accountId: Long) = 1_000_000 + accountId.toInt()

        fun cancelMessage(context: Context, accountId: Long, messageRowId: Long) {
            val system = context.getSystemService(NotificationManager::class.java)
            val own = messageRowId.toInt()
            system.cancel(tag(accountId), own)
            val others = system.activeNotifications.count { it.tag == tag(accountId) && it.id != own && it.id != summaryId(accountId) && it.id != reauthId(accountId) }
            if (others == 0) system.cancel(tag(accountId), summaryId(accountId))
        }
        const val ACCOUNT_CHANNEL_PREFIX = "mail_account_"
        const val REAUTH_CHANNEL = "reauth"
        const val ACCOUNT_GROUP = "accounts"
        const val AGENT_CHANNEL = "agent"
        const val AGENT_TAG = "agent"
        const val AGENT_REQUEST = 2_000_000
        const val SEND_CHANNEL = "send"
        const val SEND_TAG = "send"
        const val SEND_REQUEST = 3_000_000
    }
}
