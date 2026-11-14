package com.cboyxd.gitlab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.repository.JobTrace
import com.cboyxd.gitlab.ui.components.DiffDisplayOptions
import com.cboyxd.gitlab.ui.components.DiffKind
import com.cboyxd.gitlab.ui.components.DiffLine
import com.cboyxd.gitlab.ui.components.DiffLineRow
import com.cboyxd.gitlab.ui.components.LocalDiffDisplayOptions
import com.cboyxd.gitlab.ui.components.rememberDiffGutterWidth
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.viewmodel.JobDetailState
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies diff line-number gutters and job-log columns don't clip at 200% font scale. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LargeFontScreenshotTest {

  @get:Rule val rule = createComposeRule()

  @Composable
  private fun At200PercentFontScale(dark: Boolean, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale = 2f)) {
      GitLabTheme(darkTheme = dark, reducedMotion = true, content = content)
    }
  }

  private fun captureDiff(dark: Boolean) {
    val lines = listOf(
      DiffLine(DiffKind.Hunk, null, null, "@@ -9998,3 +9998,3 @@"),
      DiffLine(DiffKind.Context, 9998, 9998, "fun launch() {"),
      DiffLine(DiffKind.Remove, 9999, null, "  abort()"),
      DiffLine(DiffKind.Add, null, 9999, "  ignite()"),
      DiffLine(DiffKind.Context, 10000, 10000, "}"),
    )
    rule.setContent {
      At200PercentFontScale(dark) {
        CompositionLocalProvider(LocalDiffDisplayOptions provides DiffDisplayOptions(fontSize = 18)) {
          val gutter = rememberDiffGutterWidth(10000)
          Column(Modifier.fillMaxSize().background(Gl.colors.bg)) {
            lines.forEach { DiffLineRow(it, null, gutter) }
          }
        }
      }
    }
    rule.waitForIdle()
    rule.onAllNodesWithText("10000").onFirst().assertIsDisplayed()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/large_font_diff_${if (dark) "dark" else "light"}.png")
  }

  private fun captureJobLog(dark: Boolean) {
    val trace = listOf(
      "2026-11-14T12:34:56.789Z 00O+ Starting build",
      "2026-11-14T12:35:01.234Z 00O+ Compiling 124 files",
      "2026-11-14T12:35:10.999Z 00O+ Job succeeded",
    ).joinToString("\n") + "\n"
    rule.setContent {
      At200PercentFontScale(dark) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(GitLabJob(id = 41, name = "build", stage = "build", status = "success"), loaded = true),
            trace = Loadable(JobTrace(trace, truncated = false), loaded = true),
          ),
          canWrite = true,
          onBack = {},
          onRetryLoad = {},
          onRetryJob = {},
          onOpenInBrowser = {},
        )
      }
    }
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodesWithText("Job succeeded", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithContentDescription("Show timestamps").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("12:34:56.789").assertIsDisplayed()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/large_font_job_log_${if (dark) "dark" else "light"}.png")
  }

  @Test fun diff_200_percent_light() = captureDiff(dark = false)
  @Test fun diff_200_percent_dark() = captureDiff(dark = true)
  @Test fun job_log_200_percent_light() = captureJobLog(dark = false)
  @Test fun job_log_200_percent_dark() = captureJobLog(dark = true)
}
