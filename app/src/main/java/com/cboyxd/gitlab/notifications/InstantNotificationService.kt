package com.cboyxd.gitlab.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cboyxd.gitlab.MainActivity
import com.cboyxd.gitlab.R
import com.cboyxd.gitlab.data.auth.GitLabSessionTokens
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opt-in "Instant notifications": a foreground service that checks every minute or two while
 * the screen is on, instead of waiting for WorkManager, which Android may defer for hours.
 *
 * Each round makes one light request per account ([ChangeProbe]) and runs the full
 * [GitLabNotificationMonitor.poll] only when something changed, at least every
 * [InstantPolicy.FULL_POLL_EVERY]. With the screen off an allow-while-idle alarm wakes the loop,
 * since a sleeping CPU doesn't run coroutine timers.
 */
class InstantNotificationService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val probe = ChangeProbe()
  private var loop: Job? = null
  /** Accounts and interval the ongoing notification shows, to update it only when they change. */
  private var shown = ""

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      wake.trySend(Unit)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    running = this
    ContextCompat.registerReceiver(
      this,
      screenReceiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_TURN_OFF) {
      InstantSettings(this).enabled = false
      shutDown()
      return START_NOT_STICKY
    }
    val accounts = accounts()
    if (!InstantSettings(this).enabled || accounts.isEmpty()) {
      shutDown()
      return START_NOT_STICKY
    }
    if (!goForeground(accounts.size)) {
      shutDown()
      return START_NOT_STICKY
    }
    if (loop?.isActive == true) wake.trySend(Unit) else loop = scope.launch { run() }
    return START_STICKY
  }

  override fun onDestroy() {
    running = null
    scope.cancel()
    runCatching { unregisterReceiver(screenReceiver) }
    alarmManager()?.cancel(alarmIntent(this))
    super.onDestroy()
  }

  private fun accounts(): List<GitLabInstance> =
    runCatching { InstanceStore.get(this).getInstances().filter { it.token.isNotBlank() } }.getOrDefault(emptyList())

  private fun shutDown() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun goForeground(accounts: Int): Boolean {
    val key = "$accounts:${InstantSettings(this).intervalSeconds}"
    if (key == shown) return true
    return try {
      ServiceCompat.startForeground(
        this,
        ONGOING_ID,
        ongoingNotification(accounts),
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
      )
      shown = key
      true
    } catch (_: Exception) {
      // Android 12+ refuses foreground starts from the background (a restart after the process
      // was killed, say). WorkManager keeps checking; the app restarts the service when opened.
      false
    }
  }

  private fun ongoingNotification(accounts: Int): android.app.Notification {
    createChannel(this)
    val open = PendingIntent.getActivity(
      this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val turnOff = PendingIntent.getService(
      this, 2, Intent(this, InstantNotificationService::class.java).setAction(ACTION_TURN_OFF),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val seconds = InstantSettings(this).intervalSeconds
    return NotificationCompat.Builder(this, WATCH_CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(if (accounts == 1) "Watching 1 account" else "Watching $accounts accounts")
      .setContentText("Checks every $seconds s while the screen is on")
      .setContentIntent(open)
      .addAction(0, "Turn off", turnOff)
      .setOngoing(true)
      .setSilent(true)
      .setShowWhen(false)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  private suspend fun run() {
    val power = getSystemService(PowerManager::class.java)
    var failures = 0
    var lastFullPoll = 0L
    while (scope.isActive) {
      val settings = InstantSettings(this)
      val accounts = accounts()
      if (!settings.enabled || accounts.isEmpty()) {
        shutDown()
        return
      }
      goForeground(accounts.size)
      val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GitLabMobile:instant")
      lock?.acquire(WAKE_LOCK_TIMEOUT)
      val round = try {
        checkOnce(accounts, lastFullPoll)
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (error: Exception) {
        Round(failed = true)
      } finally {
        if (lock?.isHeld == true) lock.release()
      }
      if (round.fullPollAt != null) lastFullPoll = round.fullPollAt
      failures = if (round.failed) failures + 1 else 0
      val state = when {
        power == null || power.isInteractive -> DeviceState.SCREEN_ON
        power.isDeviceIdleMode -> DeviceState.IDLE
        else -> DeviceState.SCREEN_OFF
      }
      val delayMs = InstantPolicy.nextDelay(state, settings.intervalSeconds * 1000L, failures, round.retryAfterMs)
      if (state == DeviceState.SCREEN_ON) alarmManager()?.cancel(alarmIntent(this)) else scheduleAlarm(delayMs)
      withTimeoutOrNull(delayMs) { wake.receive() }
    }
  }

  private class Round(val failed: Boolean = false, val retryAfterMs: Long? = null, val fullPollAt: Long? = null)

  private suspend fun checkOnce(accounts: List<GitLabInstance>, lastFullPoll: Long): Round {
    val started = System.currentTimeMillis()
    val store = InstanceStore.get(this)
    var changed: Boolean? = false
    var retryAfter: Long? = null
    val results = accounts.map { account ->
      val current = GitLabSessionTokens.fresh(store, account.id) ?: account
      when (val result = probe.check(current)) {
        ProbeResult.Changed -> { changed = true; AccountPoll(account.id, account.name, ok = true) }
        ProbeResult.Unchanged -> AccountPoll(account.id, account.name, ok = true)
        is ProbeResult.RateLimited -> {
          retryAfter = maxOf(retryAfter ?: 0L, result.retryAfterMs)
          AccountPoll(account.id, account.name, ok = false, error = "Rate limited, waiting ${result.retryAfterMs / 1000} s")
        }
        is ProbeResult.Failed -> {
          // An expired token is handled (refreshed or reported) by the full check.
          if (result.authError) changed = true
          AccountPoll(account.id, account.name, ok = false, error = result.message)
        }
      }
    }
    val reachable = results.any { it.ok } || changed == true
    if (retryAfter == null && reachable && InstantPolicy.shouldFullPoll(changed, lastFullPoll, started)) {
      val ok = GitLabNotificationMonitor.poll(this, PollTrigger.INSTANT)
      return Round(failed = !ok, fullPollAt = started)
    }
    PollLog(this).record(
      PollRun(started, System.currentTimeMillis() - started, PollTrigger.INSTANT,
        if (results.all { it.ok }) PollOutcome.UNCHANGED else PollRun.outcomeOf(results), results),
    )
    return Round(failed = results.any { !it.ok }, retryAfterMs = retryAfter)
  }

  private fun alarmManager() = getSystemService(AlarmManager::class.java)

  private fun scheduleAlarm(delayMs: Long) {
    alarmManager()?.setAndAllowWhileIdle(
      AlarmManager.ELAPSED_REALTIME_WAKEUP,
      SystemClock.elapsedRealtime() + delayMs,
      alarmIntent(this),
    )
  }

  internal fun wakeNow() {
    wake.trySend(Unit)
  }

  companion object {
    const val WATCH_CHANNEL = "gitlab_watch"
    private const val ONGOING_ID = 7001
    private const val ACTION_TURN_OFF = "com.cboyxd.gitlab.INSTANT_TURN_OFF"
    private const val WAKE_LOCK_TIMEOUT = 2 * 60_000L

    @Volatile
    private var running: InstantNotificationService? = null

    val isRunning: Boolean get() = running != null

    internal fun createChannel(context: Context) {
      if (Build.VERSION.SDK_INT < 26) return
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(WATCH_CHANNEL, "Instant notifications service", NotificationManager.IMPORTANCE_MIN).apply {
          description = "Shown while GitLab Mobile checks your accounts every minute or two"
          setShowBadge(false)
        },
      )
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
      context, 3, Intent(context, InstantAlarmReceiver::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Starts or stops the service to match the setting and the signed-in accounts. Returns
     * whether it should now be running. Starting can fail while the app is in the background on
     * Android 12+; the next call from the foreground starts it.
     */
    fun sync(context: Context): Boolean {
      val app = context.applicationContext
      val intent = Intent(app, InstantNotificationService::class.java)
      val hasAccounts = runCatching { InstanceStore.get(app).getInstances().any { it.token.isNotBlank() } }.getOrDefault(false)
      if (!InstantSettings(app).enabled || !hasAccounts) {
        if (running != null) app.stopService(intent)
        return false
      }
      return try {
        ContextCompat.startForegroundService(app, intent)
        true
      } catch (_: Exception) {
        false
      }
    }

    /** From the alarm: wake the loop of a running service. */
    internal fun wake(context: Context) {
      running?.wakeNow() ?: sync(context)
    }
  }
}

/** Fires while the screen is off, when the loop's own timer can't. */
class InstantAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    InstantNotificationService.wake(context)
  }
}

/** Restarts instant notifications after a reboot or an app update. */
class InstantStartReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      InstantNotificationService.sync(context)
    }
  }
}
