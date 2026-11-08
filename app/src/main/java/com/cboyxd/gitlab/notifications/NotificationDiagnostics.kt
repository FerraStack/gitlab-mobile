package com.cboyxd.gitlab.notifications

/**
 * Android's App Standby Buckets. Each bucket limits how often background work may run; the
 * delays are the documented job limits (developer.android.com/topic/performance/appstandby).
 */
enum class StandbyBucket(val label: String, val limit: String) {
  ACTIVE("Active", "Background checks run on schedule."),
  WORKING_SET("Working set", "Android may delay background checks by up to 2 hours."),
  FREQUENT("Frequent", "Android may delay background checks by up to 8 hours."),
  RARE("Rare", "Android may delay background checks by up to 24 hours."),
  RESTRICTED("Restricted", "Android runs background checks about once a day."),
  ;

  companion object {
    /** Maps UsageStatsManager.STANDBY_BUCKET_* values (exempted counts as active). */
    fun of(value: Int): StandbyBucket = when {
      value <= 10 -> ACTIVE
      value <= 20 -> WORKING_SET
      value <= 30 -> FREQUENT
      value <= 40 -> RARE
      else -> RESTRICTED
    }
  }
}

enum class DataSaver { OFF, ON, ON_ALLOWED }

data class ChannelState(val id: String, val name: String, val enabled: Boolean)

/** The periodic WorkManager job as WorkManager reports it. */
data class ScheduledCheck(
  val state: String,
  /** When WorkManager next intends to run it; Android may still defer it. */
  val nextRunAt: Long?,
  val attempts: Int,
  /** Why the system stopped the last run, if it did (JobParameters.STOP_REASON_*). */
  val stopReason: String? = null,
)

data class AccountStatus(
  val id: String,
  val name: String,
  val lastCheckAt: Long? = null,
  val lastOk: Boolean? = null,
  val lastError: String? = null,
  val lastSuccessAt: Long? = null,
)

enum class Severity { PROBLEM, WARNING, OK }

data class Finding(val severity: Severity, val title: String, val detail: String)

/** Everything the diagnostics screen shows; built by [NotificationDiagnosticsReader]. */
data class NotificationDiagnostics(
  val now: Long,
  val permissionGranted: Boolean,
  val appNotificationsEnabled: Boolean,
  val channels: List<ChannelState>,
  /** Null below Android 9. */
  val standbyBucket: StandbyBucket?,
  val ignoringBatteryOptimizations: Boolean,
  /** Null below Android 9. */
  val backgroundRestricted: Boolean?,
  val dataSaver: DataSaver,
  val powerSaveMode: Boolean,
  val scheduled: ScheduledCheck?,
  val accounts: List<AccountStatus>,
  /** Newest first. */
  val history: List<PollRun>,
  val instantEnabled: Boolean = false,
  val instantRunning: Boolean = false,
) {
  val instantRuns: List<PollRun> get() = history.filter { it.trigger == PollTrigger.INSTANT }

  val backgroundRuns: List<PollRun> get() = history.filter { it.trigger.background }

  /** Median time between background checks in the history, or null with fewer than 3 checks. */
  val typicalInterval: Long?
    get() {
      val gaps = backgroundRuns.map { it.startedAt }.zipWithNext { newer, older -> newer - older }.sorted()
      return if (gaps.size < 2) null else gaps[gaps.size / 2]
    }

  /** Longest time without a background check, including the time since the last one. */
  val longestGap: Long?
    get() {
      val starts = backgroundRuns.map { it.startedAt }
      if (starts.isEmpty()) return null
      val gaps = starts.zipWithNext { newer, older -> newer - older } + (now - starts.first())
      return gaps.max()
    }

  fun findings(): List<Finding> = buildList {
    if (accounts.isEmpty()) {
      add(Finding(Severity.WARNING, "No signed-in accounts", "Notifications are checked only for accounts you're signed in to."))
      return@buildList
    }
    if (!permissionGranted) {
      add(Finding(Severity.PROBLEM, "Notification permission is off", "Allow notifications for GitLab Mobile in Android settings."))
    } else if (!appNotificationsEnabled) {
      add(Finding(Severity.PROBLEM, "Notifications are turned off", "Turn on notifications for GitLab Mobile in Android settings."))
    }
    channels.filter { !it.enabled }.forEach {
      add(Finding(Severity.WARNING, "\"${it.name}\" is turned off", "Android won't show this kind of notification."))
    }
    if (backgroundRestricted == true) {
      add(Finding(Severity.PROBLEM, "Background use is restricted", "Android stops background checks entirely. Allow background use in the app's battery settings."))
    }
    when (standbyBucket) {
      StandbyBucket.RARE, StandbyBucket.RESTRICTED ->
        add(Finding(Severity.PROBLEM, "Standby bucket: ${standbyBucket.label}", standbyBucket.limit + " Opening the app more often moves it to a better bucket."))
      StandbyBucket.FREQUENT, StandbyBucket.WORKING_SET ->
        add(Finding(Severity.WARNING, "Standby bucket: ${standbyBucket.label}", standbyBucket.limit))
      else -> Unit
    }
    if (!ignoringBatteryOptimizations) {
      add(Finding(Severity.WARNING, "Battery optimization is on", "While the phone is idle, Doze postpones background checks to short maintenance windows."))
    }
    if (dataSaver == DataSaver.ON) {
      add(Finding(Severity.WARNING, "Data Saver is on", "Background checks can't use mobile data."))
    }
    if (powerSaveMode) {
      add(Finding(Severity.WARNING, "Battery Saver is on", "Android defers background work more aggressively."))
    }
    if (instantEnabled && !instantRunning) {
      add(Finding(Severity.PROBLEM, "Instant notifications aren't running", "Android stopped the service. Opening the app starts it again."))
    }
    if (scheduled == null || scheduled.state in setOf("CANCELLED", "FAILED")) {
      add(Finding(Severity.PROBLEM, "No background check is scheduled", "Open the app once to schedule it again."))
    }
    accounts.filter { it.lastOk == false }.forEach {
      add(Finding(Severity.PROBLEM, "${it.name}: last check failed", it.lastError ?: "Unknown error"))
    }
    val last = backgroundRuns.firstOrNull()
    if (last != null && now - last.startedAt > STALE_AFTER) {
      add(Finding(Severity.WARNING, "Last background check ${formatDuration(now - last.startedAt)} ago", "It should run about every 15 minutes."))
    }
    if (isEmpty()) add(Finding(Severity.OK, "Nothing is holding notifications back", "Background checks should run about every 15 minutes."))
  }

  companion object {
    const val STALE_AFTER = 60 * 60 * 1000L

    /** Per-account status from the history (newest first). */
    fun accountStatuses(accounts: List<Pair<String, String>>, history: List<PollRun>): List<AccountStatus> =
      accounts.map { (id, name) ->
        val polls = history.mapNotNull { run -> run.accounts.firstOrNull { it.accountId == id }?.let { run to it } }
        val last = polls.firstOrNull()
        AccountStatus(
          id = id,
          name = name,
          lastCheckAt = last?.first?.startedAt,
          lastOk = last?.second?.ok,
          lastError = last?.second?.error,
          lastSuccessAt = polls.firstOrNull { it.second.ok }?.first?.startedAt,
        )
      }

    /** Reasons from JobParameters / WorkInfo STOP_REASON_* worth explaining. */
    fun stopReasonLabel(reason: Int): String? = when (reason) {
      3 -> "Timed out"
      4 -> "Device state (Doze)"
      7 -> "No network"
      10 -> "Out of background quota"
      11 -> "Background restriction"
      12 -> "App standby"
      13 -> "Stopped by the user"
      14 -> "System processing"
      else -> null
    }
  }
}

/** "45 s", "12 min", "3 h 5 min", "2 d 4 h". */
fun formatDuration(ms: Long): String {
  val seconds = (ms / 1000).coerceAtLeast(0)
  val minutes = seconds / 60
  val hours = minutes / 60
  val days = hours / 24
  return when {
    seconds < 60 -> "$seconds s"
    minutes < 60 -> "$minutes min"
    hours < 24 -> if (minutes % 60 == 0L) "$hours h" else "$hours h ${minutes % 60} min"
    else -> if (hours % 24 == 0L) "$days d" else "$days d ${hours % 24} h"
  }
}
