package com.cboyxd.gitlab

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cboyxd.gitlab.notifications.AccountPoll
import com.cboyxd.gitlab.notifications.BatteryHelp
import com.cboyxd.gitlab.notifications.ChannelState
import com.cboyxd.gitlab.notifications.DataSaver
import com.cboyxd.gitlab.notifications.NotificationDiagnostics
import com.cboyxd.gitlab.notifications.PollOutcome
import com.cboyxd.gitlab.notifications.PollRun
import com.cboyxd.gitlab.notifications.PollTrigger
import com.cboyxd.gitlab.notifications.ScheduledCheck
import com.cboyxd.gitlab.notifications.StandbyBucket
import com.cboyxd.gitlab.ui.screens.NotificationDiagnosticsScreen
import com.cboyxd.gitlab.ui.screens.NotificationSettingsScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Notification settings and diagnostics with an invented, typical "late notifications" phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NotificationScreensTest {

  @get:Rule val rule = createComposeRule()

  private val minute = 60_000L
  private val hour = 60 * minute
  // Fixed clock and zone so the history's times are the same on every machine.
  private val now = 1_799_996_400_000L

  private val zone = TimeZone.getDefault()

  @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  @After fun restoreZone() = TimeZone.setDefault(zone)

  private fun run(ago: Long, trigger: PollTrigger = PollTrigger.PERIODIC, error: String? = null, delivered: Int = 0) = PollRun(
    startedAt = now - ago,
    durationMs = 1_400,
    trigger = trigger,
    outcome = if (error == null) PollOutcome.OK else PollOutcome.FAILED,
    accounts = listOf(AccountPoll("a", "GitLab.com", ok = error == null, error = error, delivered = delivered)),
  )

  private fun latePhone(): NotificationDiagnostics {
    val history = listOf(
      run(2 * minute, PollTrigger.APP_OPEN, delivered = 1),
      run(3 * hour + 10 * minute),
      run(9 * hour + 40 * minute, delivered = 2),
      run(9 * hour + 55 * minute, error = "Couldn't reach GitLab."),
      run(20 * hour),
    )
    return NotificationDiagnostics(
      now = now,
      permissionGranted = true,
      appNotificationsEnabled = true,
      channels = listOf(
        ChannelState("gitlab_reviews", "Assignments and reviews", true),
        ChannelState("gitlab_activity", "GitLab activity", true),
        ChannelState("gitlab_pipelines", "Pipeline failures", false),
      ),
      standbyBucket = StandbyBucket.RARE,
      ignoringBatteryOptimizations = false,
      backgroundRestricted = false,
      dataSaver = DataSaver.OFF,
      powerSaveMode = false,
      scheduled = ScheduledCheck("ENQUEUED", now - 2 * hour, 0, stopReason = "App standby"),
      accounts = NotificationDiagnostics.accountStatuses(listOf("a" to "GitLab.com"), history),
      history = history,
    )
  }

  private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
    rule.setContent { GitLabTheme(darkTheme = dark, reducedMotion = true, content = content) }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Composable
  private fun Diagnostics(onCheckNow: () -> Unit = {}) = NotificationDiagnosticsScreen(
    diagnostics = latePhone(),
    checking = false,
    onBack = {},
    onCheckNow = onCheckNow,
    onOpenSystemSettings = {},
  )

  @Composable
  private fun SettingsScreen(
    instant: Boolean = false,
    batteryCard: Boolean = false,
    onSetInstant: (Boolean) -> Unit = {},
    onAllow: () -> Unit = {},
  ) = NotificationSettingsScreen(
    diagnostics = latePhone(),
    onBack = {},
    onOpenSystemSettings = {},
    onOpenDiagnostics = {},
    instantEnabled = instant,
    intervalSeconds = 90,
    onSetInstant = onSetInstant,
    battery = BatteryHelp.forManufacturer("Xiaomi"),
    showBatteryCard = batteryCard,
    onAllowBackground = onAllow,
  )

  @Test fun diagnostics_light() = capture("notification_diagnostics", dark = false) { Diagnostics() }
  @Test fun diagnostics_dark() = capture("notification_diagnostics", dark = true) { Diagnostics() }
  @Test fun settings_light() = capture("notification_settings", dark = false) { SettingsScreen() }
  @Test fun settings_dark() = capture("notification_settings", dark = true) { SettingsScreen() }
  @Test fun settings_instant_light() = capture("notification_settings_instant", dark = false) { SettingsScreen(instant = true) }
  @Test fun settings_instant_dark() = capture("notification_settings_instant", dark = true) { SettingsScreen(instant = true) }
  @Test fun battery_card_light() = capture("notification_battery_card", dark = false) { SettingsScreen(instant = true, batteryCard = true) }
  @Test fun battery_card_dark() = capture("notification_battery_card", dark = true) { SettingsScreen(instant = true, batteryCard = true) }

  @Test
  fun `the battery card asks to allow background use`() {
    var allowed = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) { SettingsScreen(instant = true, batteryCard = true, onAllow = { allowed++ }) }
    }
    rule.onNodeWithText("Keep instant notifications running").assertExists()
    rule.onNodeWithTag("battery_allow").performClick()
    assertEquals(1, allowed)
  }

  @Test
  fun `the instant switch reports the new value`() {
    val values = mutableListOf<Boolean>()
    rule.setContent { GitLabTheme(darkTheme = true, reducedMotion = true) { SettingsScreen(onSetInstant = { values += it }) } }
    rule.onNodeWithTag("instant_toggle").performClick()
    assertEquals(listOf(true), values)
  }

  @Test
  fun `diagnostics explain the delay and check on demand`() {
    var checks = 0
    rule.setContent { GitLabTheme(darkTheme = true, reducedMotion = true) { Diagnostics(onCheckNow = { checks++ }) } }
    rule.onNodeWithText("Standby bucket: Rare").assertExists()
    rule.onNodeWithText("Last background check 3 h 10 min ago").assertExists()
    rule.onNodeWithTag("check_now").performClick()
    assertEquals(1, checks)
  }

  @Test
  fun `settings summarize what can delay notifications`() {
    rule.setContent { GitLabTheme(darkTheme = true, reducedMotion = true) { SettingsScreen() } }
    rule.onNodeWithText("4 things can delay notifications").assertExists()
  }
}
