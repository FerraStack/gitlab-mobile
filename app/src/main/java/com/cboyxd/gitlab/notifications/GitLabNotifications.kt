package com.cboyxd.gitlab.notifications

import android.Manifest
import android.app.Notification
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cboyxd.gitlab.MainActivity
import com.cboyxd.gitlab.R
import java.util.concurrent.TimeUnit

object GitLabNotifications {
  const val ACTION_OPEN = "com.cboyxd.gitlab.OPEN_NOTIFICATION"
  const val EXTRA_ACCOUNT = "account_id"
  const val EXTRA_TARGET = "target_type"
  const val EXTRA_PROJECT = "project_id"
  const val EXTRA_IID = "target_iid"
  const val EXTRA_URL = "target_url"
  internal const val REVIEWS = "gitlab_reviews"
  internal const val ACTIVITY = "gitlab_activity"
  internal const val PIPELINES = "gitlab_pipelines"
  internal const val PERIODIC = "gitlab_notification_poll"
  private const val IMMEDIATE = "gitlab_notification_check"
  /** Tags the one-off check so the worker can tell it from the periodic one in diagnostics. */
  internal const val TAG_ONE_TIME = "notification_check_one_time"
  internal val CHANNEL_IDS = listOf(REVIEWS, ACTIVITY, PIPELINES)

  fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    listOf(
      Triple(REVIEWS, "Assignments and reviews", "Merge requests assigned to you and requests for review"),
      Triple(ACTIVITY, "GitLab activity", "Your merge requests, mentions and discussions"),
      Triple(PIPELINES, "Pipeline failures", "Failed pipelines that GitLab adds to your to-do list"),
    ).forEach { (id, title, description) ->
      manager.createNotificationChannel(NotificationChannel(id, title, NotificationManager.IMPORTANCE_DEFAULT).apply { this.description = description })
    }
  }

  fun permissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun allowed(context: Context): Boolean =
    permissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

  fun schedule(context: Context, hasAccounts: Boolean) {
    createChannels(context)
    val work = WorkManager.getInstance(context)
    if (!hasAccounts) {
      work.cancelUniqueWork(PERIODIC)
      work.cancelUniqueWork(IMMEDIATE)
      NotificationManagerCompat.from(context).cancelAll()
      return
    }
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<GitLabNotificationWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<GitLabNotificationWorker>().setConstraints(constraints).addTag(TAG_ONE_TIME).build())
  }

  /** Posts [event]; false when notifications are off or the permission was just revoked. */
  internal fun show(context: Context, event: GitLabNotificationEvent, now: Long = System.currentTimeMillis()): Boolean {
    if (!allowed(context)) return false
    val intent = Intent(context, MainActivity::class.java).apply {
      action = ACTION_OPEN
      data = Uri.Builder().scheme("gitlab-mobile").authority("notification").appendPath(event.accountId).appendPath(event.id).build()
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra(EXTRA_ACCOUNT, event.accountId)
      putExtra(EXTRA_TARGET, event.targetType)
      putExtra(EXTRA_PROJECT, event.projectId)
      putExtra(EXTRA_IID, event.iid)
      putExtra(EXTRA_URL, event.url)
    }
    val click = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    // A late check must not make an old event look new: say how old it is instead of a bare time.
    val delay = NotificationPresentation.delayLabel(event.createdAt, now)
    val notification = NotificationCompat.Builder(context, event.channel)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(event.title)
      .setContentText(event.message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
      .setContentIntent(click)
      .setAutoCancel(true)
      .setWhen(event.createdAt)
      .setShowWhen(delay == null)
      .setSubText(delay)
      .setCategory(NotificationCompat.CATEGORY_SOCIAL)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setGroup(groupKey(event.accountId))
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .build()
    return try {
      NotificationManagerCompat.from(context).notify("${event.accountId}:${event.id}", 0, notification)
      updateSummary(context, event.accountId, event.accountName)
      true
    } catch (_: SecurityException) {
      // Permission can be revoked between the check and posting.
      false
    }
  }

  private fun groupKey(accountId: String) = "gitlab:$accountId"

  private const val SUMMARY = "summary"

  /**
   * One InboxStyle summary per account once it has two or more visible events, so several
   * updates collapse into "3 GitLab updates" with a line each instead of a pile of cards.
   */
  @SuppressLint("MissingPermission")
  internal fun updateSummary(context: Context, accountId: String, accountName: String) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val summaryTag = "$accountId:$SUMMARY"
    val events = manager.activeNotifications
      .filter { it.tag?.startsWith("$accountId:") == true && it.tag != summaryTag }
      .sortedByDescending { it.notification.`when` }
      .map {
        val extras = it.notification.extras
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() to
          extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
      }
    val summary = NotificationPresentation.summary(accountName, events)
    if (summary == null) {
      manager.cancel(summaryTag, 0)
      return
    }
    val style = NotificationCompat.InboxStyle()
      .setBigContentTitle(summary.title)
      .setSummaryText(summary.summaryText)
    summary.lines.forEach { style.addLine(it) }
    val open = PendingIntent.getActivity(
      context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, ACTIVITY)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(summary.title)
      .setContentText(summary.lines.first())
      .setSubText(summary.summaryText)
      .setStyle(style)
      .setContentIntent(open)
      .setAutoCancel(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SOCIAL)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setGroup(groupKey(accountId))
      .setGroupSummary(true)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(summaryTag, 0, notification)
    } catch (_: SecurityException) {
      // Permission revoked meanwhile.
    }
  }
}
