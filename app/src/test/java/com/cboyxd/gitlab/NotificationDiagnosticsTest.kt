package com.cboyxd.gitlab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.notifications.AccountPoll
import com.cboyxd.gitlab.notifications.ChannelState
import com.cboyxd.gitlab.notifications.DataSaver
import com.cboyxd.gitlab.notifications.NotificationDiagnostics
import com.cboyxd.gitlab.notifications.PollLog
import com.cboyxd.gitlab.notifications.PollOutcome
import com.cboyxd.gitlab.notifications.PollRun
import com.cboyxd.gitlab.notifications.PollTrigger
import com.cboyxd.gitlab.notifications.ScheduledCheck
import com.cboyxd.gitlab.notifications.Severity
import com.cboyxd.gitlab.notifications.StandbyBucket
import com.cboyxd.gitlab.notifications.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Poll history and the reasoning behind "why are my notifications late". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationDiagnosticsTest {

  private val minute = 60_000L
  private val hour = 60 * minute
  private val now = 1_800_000_000_000L
  private lateinit var log: PollLog

  @Before
  fun setUp() {
    log = PollLog(ApplicationProvider.getApplicationContext<Context>())
    log.clear()
  }

  private fun run(at: Long, trigger: PollTrigger = PollTrigger.PERIODIC, ok: Boolean = true, delivered: Int = 0) =
    PollRun(at, 1200, trigger, if (ok) PollOutcome.OK else PollOutcome.FAILED,
      listOf(AccountPoll("a", "GitLab.com", ok, if (ok) null else "401: Unauthorized", delivered)))

  private fun healthy(history: List<PollRun> = listOf(run(now - 5 * minute))) = NotificationDiagnostics(
    now = now,
    permissionGranted = true,
    appNotificationsEnabled = true,
    channels = listOf(ChannelState("gitlab_reviews", "Assignments and reviews", true)),
    standbyBucket = StandbyBucket.ACTIVE,
    ignoringBatteryOptimizations = true,
    backgroundRestricted = false,
    dataSaver = DataSaver.OFF,
    powerSaveMode = false,
    scheduled = ScheduledCheck("ENQUEUED", now + 10 * minute, 0),
    accounts = NotificationDiagnostics.accountStatuses(listOf("a" to "GitLab.com"), history),
    history = history,
  )

  @Test
  fun `the log returns runs newest first and survives a round trip`() {
    log.record(run(now - 2 * hour, delivered = 2))
    log.record(run(now - hour, PollTrigger.APP_OPEN, ok = false))
    log.record(run(now - 3 * hour, PollTrigger.MANUAL))
    val runs = log.runs()
    assertEquals(listOf(now - hour, now - 2 * hour, now - 3 * hour), runs.map { it.startedAt })
    assertEquals(PollTrigger.APP_OPEN, runs[0].trigger)
    assertEquals("401: Unauthorized", runs[0].accounts.single().error)
    assertEquals(2, runs[1].delivered)
  }

  @Test
  fun `in-app checks don't push background checks out of the log`() {
    repeat(60) { log.record(run(now - (100 - it) * minute)) }
    repeat(40) { log.record(run(now - it * 1000L, PollTrigger.APP_OPEN)) }
    val runs = log.runs()
    assertEquals(PollLog.BACKGROUND_LIMIT, runs.count { it.trigger == PollTrigger.PERIODIC })
    assertEquals(PollLog.APP_LIMIT, runs.count { it.trigger == PollTrigger.APP_OPEN })
    // The oldest background runs were dropped, the newest kept.
    assertEquals(now - 41 * minute, runs.filter { it.trigger == PollTrigger.PERIODIC }.first().startedAt)
  }

  @Test
  fun `damaged log entries are skipped`() {
    assertTrue(PollLog.decode("not json").isEmpty())
    val good = PollLog.encode(listOf(run(now)))
    val mixed = good.dropLast(1) + """,{"start":1,"trigger":"NOPE","outcome":"OK"}]"""
    assertEquals(1, PollLog.decode(mixed).size)
  }

  @Test
  fun `typical interval is the median gap and the longest gap counts the time since the last check`() {
    val history = listOf(now - 20 * minute, now - 35 * minute, now - 50 * minute, now - 6 * hour).map { run(it) }
    val d = healthy(history)
    assertEquals(15 * minute, d.typicalInterval)
    assertEquals(6 * hour - 50 * minute, d.longestGap)
    assertNull(healthy(listOf(run(now))).typicalInterval)
  }

  @Test
  fun `in-app checks don't count as background cadence`() {
    val d = healthy(listOf(run(now - minute, PollTrigger.APP_OPEN), run(now - 3 * hour)))
    assertEquals(now - 3 * hour, d.backgroundRuns.single().startedAt)
    assertTrue(d.findings().any { it.severity == Severity.WARNING && it.title == "Last background check 3 h ago" })
  }

  @Test
  fun `a healthy phone reports nothing holding notifications back`() {
    val findings = healthy().findings()
    assertEquals(listOf(Severity.OK), findings.map { it.severity })
  }

  @Test
  fun `restrictive system settings are explained`() {
    val d = healthy().copy(
      permissionGranted = false,
      channels = listOf(ChannelState("gitlab_pipelines", "Pipeline failures", false)),
      standbyBucket = StandbyBucket.RARE,
      ignoringBatteryOptimizations = false,
      backgroundRestricted = true,
      dataSaver = DataSaver.ON,
      scheduled = null,
    )
    val titles = d.findings().associate { it.title to it.severity }
    assertEquals(Severity.PROBLEM, titles["Notification permission is off"])
    assertEquals(Severity.WARNING, titles["\"Pipeline failures\" is turned off"])
    assertEquals(Severity.PROBLEM, titles["Standby bucket: Rare"])
    assertEquals(Severity.WARNING, titles["Battery optimization is on"])
    assertEquals(Severity.PROBLEM, titles["Background use is restricted"])
    assertEquals(Severity.WARNING, titles["Data Saver is on"])
    assertEquals(Severity.PROBLEM, titles["No background check is scheduled"])
  }

  @Test
  fun `a failing account shows its last error and last success`() {
    val history = listOf(run(now - 2 * minute, ok = false), run(now - 20 * minute))
    val d = healthy(history)
    val account = d.accounts.single()
    assertEquals(false, account.lastOk)
    assertEquals("401: Unauthorized", account.lastError)
    assertEquals(now - 20 * minute, account.lastSuccessAt)
    assertTrue(d.findings().any { it.title == "GitLab.com: last check failed" && it.detail == "401: Unauthorized" })
  }

  @Test
  fun `no accounts means nothing else matters`() {
    val d = healthy().copy(accounts = emptyList(), permissionGranted = false)
    assertEquals(listOf("No signed-in accounts"), d.findings().map { it.title })
  }

  @Test
  fun `standby buckets and durations read naturally`() {
    assertEquals(StandbyBucket.ACTIVE, StandbyBucket.of(5))
    assertEquals(StandbyBucket.WORKING_SET, StandbyBucket.of(20))
    assertEquals(StandbyBucket.FREQUENT, StandbyBucket.of(30))
    assertEquals(StandbyBucket.RARE, StandbyBucket.of(40))
    assertEquals(StandbyBucket.RESTRICTED, StandbyBucket.of(45))
    assertEquals("45 s", formatDuration(45_000))
    assertEquals("12 min", formatDuration(12 * minute))
    assertEquals("3 h 5 min", formatDuration(3 * hour + 5 * minute))
    assertEquals("1 d 2 h", formatDuration(26 * hour))
  }

  @Test
  fun `outcome summarizes the accounts`() {
    val ok = AccountPoll("a", "A", ok = true)
    val bad = AccountPoll("b", "B", ok = false, error = "timeout")
    assertEquals(PollOutcome.OK, PollRun.outcomeOf(listOf(ok)))
    assertEquals(PollOutcome.PARTIAL, PollRun.outcomeOf(listOf(ok, bad)))
    assertEquals(PollOutcome.FAILED, PollRun.outcomeOf(listOf(bad)))
    assertEquals(PollOutcome.NO_ACCOUNTS, PollRun.outcomeOf(emptyList()))
  }
}
