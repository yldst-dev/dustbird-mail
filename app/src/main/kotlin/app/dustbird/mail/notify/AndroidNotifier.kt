package app.dustbird.mail.notify

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
            val identity = mail.identityLabel ?: context.getString(R.string.notify_unknown_identity)
            val title = mail.sender.ifBlank { context.getString(R.string.notify_unknown_sender) }
            val subject = mail.subject.ifBlank { context.getString(R.string.notify_no_subject) }
            val notification = NotificationCompat.Builder(context, channelId(account.id))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(subject, mail.preview).filter { it.isNotBlank() }.joinToString("\n")))
                .setSubText(context.getString(R.string.notify_subtext, accountName, identity))
                .setContentIntent(openMessage(mail.messageRowId))
                .setAutoCancel(true)
                .setGroup(groupKey(account.id))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
                .setPublicVersion(publicVersion)
                .build()
            post(tag(account.id), mail.messageRowId.toInt(), notification)
        }
        val summary = NotificationCompat.Builder(context, channelId(account.id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_summary_title, accountName))
            .setContentText(context.getString(R.string.notify_summary_text, mails.size))
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

    private fun channelId(accountId: Long) = "$ACCOUNT_CHANNEL_PREFIX$accountId"

    private fun groupKey(accountId: Long) = "account_$accountId"

    private fun tag(accountId: Long) = "account_$accountId"

    private fun summaryId(accountId: Long) = -(accountId.toInt() + 1)

    private fun reauthId(accountId: Long) = 1_000_000 + accountId.toInt()

    companion object {
        const val ACCOUNT_CHANNEL_PREFIX = "mail_account_"
        const val REAUTH_CHANNEL = "reauth"
        const val ACCOUNT_GROUP = "accounts"
    }
}
