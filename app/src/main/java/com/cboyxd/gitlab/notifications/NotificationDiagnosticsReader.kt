package com.cboyxd.gitlab.notifications

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Reads the system state that decides when background checks run. Nothing here needs a permission. */
object NotificationDiagnosticsReader {

  suspend fun read(context: Context): NotificationDiagnostics = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val accounts = runCatching {
      InstanceStore.get(app).getInstances().filter { it.token.isNotBlank() }.map { it.id to it.name }
    }.getOrDefault(emptyList())
    val history = PollLog(app).runs()
    val power = app.getSystemService(PowerManager::class.java)
    val connectivity = app.getSystemService(ConnectivityManager::class.java)
    NotificationDiagnostics(
      now = System.currentTimeMillis(),
      permissionGranted = GitLabNotifications.permissionGranted(app),
      appNotificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
      channels = channels(app),
      standbyBucket = if (Build.VERSION.SDK_INT >= 28) {
        app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.let(StandbyBucket::of)
      } else null,
      ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
      backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) {
        app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
      } else null,
      dataSaver = when (connectivity?.restrictBackgroundStatus) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> DataSaver.ON
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> DataSaver.ON_ALLOWED
        else -> DataSaver.OFF
      },
      powerSaveMode = power?.isPowerSaveMode == true,
      scheduled = scheduled(app),
      accounts = NotificationDiagnostics.accountStatuses(accounts, history),
      history = history,
    )
  }

  private fun channels(context: Context): List<ChannelState> {
    if (Build.VERSION.SDK_INT < 26) return emptyList()
    GitLabNotifications.createChannels(context)
    val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
    return GitLabNotifications.CHANNEL_IDS.mapNotNull { id ->
      manager.getNotificationChannel(id)?.let {
        ChannelState(id, it.name.toString(), it.importance != NotificationManager.IMPORTANCE_NONE)
      }
    }
  }

  private suspend fun scheduled(context: Context): ScheduledCheck? = runCatching {
    WorkManager.getInstance(context)
      .getWorkInfosForUniqueWorkFlow(GitLabNotifications.PERIODIC)
      .first()
      .firstOrNull { !it.state.isFinished } ?.let { info ->
        ScheduledCheck(
          state = info.state.name,
          nextRunAt = info.nextScheduleTimeMillis.takeIf { it in 1 until Long.MAX_VALUE },
          attempts = info.runAttemptCount,
          stopReason = if (info.stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) {
            NotificationDiagnostics.stopReasonLabel(info.stopReason)
          } else null,
        )
      }
  }.getOrNull()
}
