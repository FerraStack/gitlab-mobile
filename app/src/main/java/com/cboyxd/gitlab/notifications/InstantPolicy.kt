package com.cboyxd.gitlab.notifications

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/** What the phone is doing; it decides how often instant notifications check. */
enum class DeviceState { SCREEN_ON, SCREEN_OFF, IDLE }

/** Instant notifications' own settings. */
class InstantSettings internal constructor(private val prefs: android.content.SharedPreferences) {
  constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

  var enabled: Boolean
    get() = prefs.getBoolean(KEY_ENABLED, false)
    set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

  /** Seconds between checks while the screen is on. */
  var intervalSeconds: Int
    get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
    set(value) { prefs.edit().putInt(KEY_INTERVAL, value).apply() }

  companion object {
    const val DEFAULT_INTERVAL = 90
    val INTERVAL_CHOICES = listOf(60, 90, 120)
    private const val PREFS = "gitlab_instant_notifications"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL = "interval_seconds"
  }
}

/** Timing decisions for the instant notifications loop, kept pure so they can be tested. */
object InstantPolicy {
  const val SCREEN_OFF_INTERVAL = 5 * 60_000L
  /** Doze lets an app's allow-while-idle alarm fire about once every 9 minutes at most. */
  const val IDLE_INTERVAL = 15 * 60_000L
  const val MAX_BACKOFF = 15 * 60_000L
  /** Merged or closed merge requests don't change any count, so read everything this often. */
  const val FULL_POLL_EVERY = 10 * 60_000L

  /**
   * Delay before the next check. Errors back off exponentially from the normal interval up to
   * [MAX_BACKOFF]; a server's Retry-After is never undercut.
   */
  fun nextDelay(state: DeviceState, intervalMs: Long, failures: Int, retryAfterMs: Long? = null): Long {
    val base = when (state) {
      DeviceState.SCREEN_ON -> intervalMs
      DeviceState.SCREEN_OFF -> max(intervalMs, SCREEN_OFF_INTERVAL)
      DeviceState.IDLE -> max(intervalMs, IDLE_INTERVAL)
    }
    val backedOff = if (failures <= 0) base else {
      val factor = 1L shl min(failures, 10)
      max(base, min(intervalMs * factor, MAX_BACKOFF))
    }
    return max(backedOff, retryAfterMs ?: 0L)
  }

  /**
   * Whether to read every feed now. [changed] is the light check's answer: true or false, or
   * null when it couldn't tell (old server, error).
   */
  fun shouldFullPoll(changed: Boolean?, lastFullPollAt: Long, now: Long): Boolean =
    changed != false || now - lastFullPollAt >= FULL_POLL_EVERY
}
