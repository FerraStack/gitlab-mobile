package com.cboyxd.gitlab.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate histories, so frequent checks don't push rare ones out of the log. */
enum class PollBucket(val key: String, val limit: Int) {
  BACKGROUND("runs:background", 50),
  INSTANT("runs:instant", 40),
  APP("runs:app", 20),
}

/** What started a notification check. */
enum class PollTrigger(val label: String, val bucket: PollBucket) {
  /** WorkManager's 15-minute periodic work, which Android may defer for hours. */
  PERIODIC("Background", PollBucket.BACKGROUND),
  /** One-off work queued when accounts change. */
  ONE_TIME("After account change", PollBucket.BACKGROUND),
  /** The instant notifications service. */
  INSTANT("Instant", PollBucket.INSTANT),
  /** The loop that runs while the app is on screen. */
  APP_OPEN("App open", PollBucket.APP),
  /** "Check now" in diagnostics. */
  MANUAL("Check now", PollBucket.APP),
  ;

  /** WorkManager checks: the cadence Android allows without instant notifications. */
  val background: Boolean get() = bucket == PollBucket.BACKGROUND
}

enum class PollOutcome(val label: String) {
  OK("OK"),
  /** A light check found no new to-dos or counts, so nothing else was fetched. */
  UNCHANGED("No changes"),
  /** Some accounts or feeds failed, others were read. */
  PARTIAL("Partly failed"),
  FAILED("Failed"),
  /** Another check was already running. */
  BUSY("Skipped, another check was running"),
  NO_ACCOUNTS("No signed-in accounts"),
}

data class AccountPoll(
  val accountId: String,
  val accountName: String,
  val ok: Boolean,
  val error: String? = null,
  val delivered: Int = 0,
)

/** One run of [GitLabNotificationMonitor.poll]. */
data class PollRun(
  val startedAt: Long,
  val durationMs: Long,
  val trigger: PollTrigger,
  val outcome: PollOutcome,
  val accounts: List<AccountPoll> = emptyList(),
) {
  val delivered: Int get() = accounts.sumOf { it.delivered }

  companion object {
    fun outcomeOf(accounts: List<AccountPoll>): PollOutcome = when {
      accounts.isEmpty() -> PollOutcome.NO_ACCOUNTS
      accounts.all { it.ok } -> PollOutcome.OK
      accounts.none { it.ok } -> PollOutcome.FAILED
      else -> PollOutcome.PARTIAL
    }
  }
}

/**
 * The last notification checks, kept on the device so diagnostics can show how often Android
 * really runs them. Each [PollBucket] is kept apart: the in-app loop and instant notifications
 * run every minute or two and would otherwise push the background history out within an hour.
 */
class PollLog internal constructor(private val prefs: android.content.SharedPreferences) {
  constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

  @Synchronized
  fun record(run: PollRun) {
    val bucket = run.trigger.bucket
    val runs = decode(prefs.getString(bucket.key, null)) + run
    prefs.edit().putString(bucket.key, encode(runs.takeLast(bucket.limit))).apply()
  }

  /** All recorded runs, newest first. */
  @Synchronized
  fun runs(): List<PollRun> =
    PollBucket.entries.flatMap { decode(prefs.getString(it.key, null)) }.sortedByDescending { it.startedAt }

  @Synchronized
  fun clear() {
    prefs.edit().clear().apply()
  }

  companion object {
    val BACKGROUND_LIMIT = PollBucket.BACKGROUND.limit
    val APP_LIMIT = PollBucket.APP.limit
    private const val PREFS = "gitlab_notification_diagnostics"

    internal fun encode(runs: List<PollRun>): String = JSONArray().apply {
      runs.forEach { run ->
        put(JSONObject().apply {
          put("start", run.startedAt)
          put("ms", run.durationMs)
          put("trigger", run.trigger.name)
          put("outcome", run.outcome.name)
          put("accounts", JSONArray().apply {
            run.accounts.forEach { account ->
              put(JSONObject().apply {
                put("id", account.accountId)
                put("name", account.accountName)
                put("ok", account.ok)
                account.error?.let { put("error", it.take(300)) }
                put("delivered", account.delivered)
              })
            }
          })
        })
      }
    }.toString()

    /** Unknown or damaged entries are skipped, never fatal: this is only a diagnostic log. */
    internal fun decode(raw: String?): List<PollRun> {
      if (raw.isNullOrBlank()) return emptyList()
      val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
      return (0 until array.length()).mapNotNull { index ->
        runCatching {
          val o = array.getJSONObject(index)
          val accounts = o.optJSONArray("accounts") ?: JSONArray()
          PollRun(
            startedAt = o.getLong("start"),
            durationMs = o.optLong("ms"),
            trigger = PollTrigger.valueOf(o.getString("trigger")),
            outcome = PollOutcome.valueOf(o.getString("outcome")),
            accounts = (0 until accounts.length()).map { i ->
              val a = accounts.getJSONObject(i)
              AccountPoll(
                accountId = a.getString("id"),
                accountName = a.optString("name"),
                ok = a.optBoolean("ok"),
                error = if (a.has("error")) a.getString("error") else null,
                delivered = a.optInt("delivered"),
              )
            },
          )
        }.getOrNull()
      }
    }
  }
}
