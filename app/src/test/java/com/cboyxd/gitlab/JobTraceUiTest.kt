package com.cboyxd.gitlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.repository.JobTrace
import com.cboyxd.gitlab.ui.components.MarkdownText
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.screens.MAX_LOG_COLUMNS
import com.cboyxd.gitlab.ui.screens.MAX_LOG_WRAPPED_CHARS
import com.cboyxd.gitlab.ui.screens.jobTraceDisplayText
import com.cboyxd.gitlab.ui.screens.parseJobTrace
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.JobDetailState
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class JobTraceUiTest {

  @get:Rule val rule = createComposeRule()

  private fun runningJob(status: String = "running") = GitLabJob(
    id = 41,
    name = "build",
    stage = "build",
    status = status,
  )

  @Test
  fun `parseJobTrace tracks stream byte offsets stably across a sliding window`() {
    val lineA = "2026-11-14T10:00:00.000Z 00O+ alpha\n"
    val lineB = "2026-11-14T10:00:01.000Z 00O+ café 😀\n"
    val lineC = "2026-11-14T10:00:02.000Z 00O+ gamma\n"
    val lineD = "2026-11-14T10:00:03.000Z 00O+ delta\n"

    val baseOffset = 1000L
    val firstWindow = parseJobTrace(lineA + lineB + lineC, startOffset = baseOffset, firstLineNumber = null)
    val slidOffset = baseOffset + lineA.toByteArray(Charsets.UTF_8).size
    val secondWindow = parseJobTrace(lineB + lineC + lineD, startOffset = slidOffset, firstLineNumber = null)

    assertEquals(3, firstWindow.size)
    assertNull(firstWindow[0].number)
    // Surviving lines keep the exact same byteOffset keys when the window slides.
    assertEquals(firstWindow[1].byteOffset, secondWindow[0].byteOffset)
    assertEquals(firstWindow[2].byteOffset, secondWindow[1].byteOffset)
    assertEquals(slidOffset + lineB.toByteArray(Charsets.UTF_8).size, secondWindow[1].byteOffset)
  }

  @Test
  fun `jobTraceDisplayText caps very long lines with ellipsis`() {
    val huge = AnnotatedString("x".repeat(200_000))
    val wrapped = jobTraceDisplayText(huge, wrapped = true)
    val unwrapped = jobTraceDisplayText(huge, wrapped = false)

    assertEquals(MAX_LOG_WRAPPED_CHARS + 1, wrapped.length)
    assertTrue(wrapped.text.endsWith("…"))
    assertEquals(MAX_LOG_COLUMNS + 1, unwrapped.length)
    assertTrue(unwrapped.text.endsWith("…"))
  }

  @Test
  fun `truncated log shows earlier output banner and avoids fake line 1`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("success"), loaded = true),
            trace = Loadable(JobTrace("tail line a\ntail line b\n", truncated = true, startOffset = 744_000L, earlierLines = null), loaded = true),
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
      rule.onAllNodesWithText("tail line b", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("earlier output truncated", substring = true).assertIsDisplayed()
    rule.onNodeWithText("1").assertDoesNotExist()
  }

  @Test
  fun `truncated log with known earlier line count shows exact line numbers`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("success"), loaded = true),
            trace = Loadable(JobTrace("step 43\nstep 44\n", truncated = true, startOffset = 10_240L, earlierLines = 42), loaded = true),
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
      rule.onAllNodesWithText("step 44", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("42 earlier lines truncated", substring = true).assertIsDisplayed()
    rule.onNodeWithText("43").assertIsDisplayed()
    rule.onNodeWithText("1").assertDoesNotExist()
  }

  @Test
  fun `running job shows Follow toggle and auto-scrolls when new lines arrive`() {
    val initialLines = (1..40).joinToString("\n") { "line $it" } + "\n"
    var currentTrace by mutableStateOf(JobTrace(initialLines, truncated = false))

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("running"), loaded = true),
            trace = Loadable(currentTrace, loaded = true),
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
      rule.onAllNodesWithText("line 40", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithTag("follow_log").assertIsDisplayed().assertIsSelected()

    // Append new lines while Follow is enabled: the new tail scrolls into view automatically.
    val updatedLines = (1..80).joinToString("\n") { "line $it" } + "\n"
    currentTrace = JobTrace(updatedLines, truncated = false)
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodesWithText("line 80", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("line 80").assertIsDisplayed()

    // Toggle Follow off and on.
    rule.onNodeWithTag("follow_log").performClick()
    rule.onNodeWithTag("follow_log").assertIsNotSelected()
    rule.onNodeWithTag("follow_log").performClick()
    rule.onNodeWithTag("follow_log").assertIsSelected()
  }

  @Test
  fun `finished job does not show Follow toggle`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("success"), loaded = true),
            trace = Loadable(JobTrace("Job succeeded\n", truncated = false), loaded = true),
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
    rule.onNodeWithTag("follow_log").assertDoesNotExist()
  }

  @Test
  fun `very long job log line renders capped without freezing`() {
    val huge = "A".repeat(150_000) + "\n"
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("success"), loaded = true),
            trace = Loadable(JobTrace(huge, truncated = false), loaded = true),
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
      rule.onAllNodesWithText("…", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("…", substring = true).assertIsDisplayed()
  }

  @Test
  fun `collapsible job log sections have 48dp touch targets and announce section title`() {
    val trace = "section_start:1700:build_step\r\u001B[0KBuild\necho building\nsection_end:1700:build_step\r\u001B[0K\n"
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 41,
            job = Loadable(runningJob("success"), loaded = true),
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
      rule.onAllNodesWithText("echo building", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithContentDescription("Collapse section: Build")
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()

    rule.onNodeWithContentDescription("Expand section: Build").assertIsDisplayed()
    rule.onNodeWithText("echo building").assertDoesNotExist()
  }

  @Test
  fun `linked markdown badges have at least 48dp touch targets`() {
    rule.setContent {
      GitLabTheme(darkTheme = false, reducedMotion = true) {
        MarkdownText(markdown = "[![Build Status](https://example.com/badge.svg)](https://example.com/pipeline)")
      }
    }
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onNodeWithContentDescription("Build Status").run {
        runCatching { fetchSemanticsNode() }.isSuccess
      }
    }
    rule.onNodeWithContentDescription("Build Status")
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
  }
}
