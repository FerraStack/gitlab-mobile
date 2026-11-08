package com.cboyxd.gitlab

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.model.MergeOptions
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.screens.mergeWaitsForPipeline
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Merge options, auto-merge while the pipeline runs, and canceling auto-merge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class MergeOptionsTest {

  @get:Rule val rule = createComposeRule()
  private val merges = mutableListOf<MergeOptions>()
  private var cancels = 0

  private fun show(mr: GitLabMergeRequest, dark: Boolean = true) {
    val base = Fakes.mergeRequestDetail()
    rule.setContent {
      GitLabTheme(darkTheme = dark, reducedMotion = true) {
        MergeRequestDetailScreen(
          state = base.copy(mergeRequest = base.mergeRequest.copy(data = mr)),
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onMerge = { merges += it },
          onSetClosed = {},
          onComment = { _, _ -> },
          onOpenPipeline = {},
          onOpenCommit = {},
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          onCancelAutoMerge = { cancels++ },
        )
      }
    }
  }

  private val mr = Fakes.mergeRequests.first().copy(forceRemoveSourceBranch = true, squash = false)

  private fun openDialog() {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("merge_button"))
    rule.onNodeWithTag("merge_button").performClick()
    rule.waitForIdle()
  }

  @Test
  fun `the dialog starts from the author's choices and sends what was picked`() {
    show(mr)
    openDialog()
    rule.onNodeWithTag("merge_delete_branch").assertIsOn()
    rule.onNodeWithTag("merge_squash").assertIsOff()
    rule.onNodeWithTag("merge_squash").performClick()
    rule.onNodeWithText("Merge", useUnmergedTree = true).assertExists()
    rule.onNodeWithText("Merge !12 into main?").assertExists()
    rule.onNode(hasTestTag("merge_squash")).assertIsOn()
    rule.onNodeWithText("Merge !12 into main?").assertExists()
  }

  @Test
  fun `a running pipeline turns merge into auto-merge`() {
    val running = mr.copy(headPipeline = GitLabPipeline(id = 900, status = "running"))
    assertTrue(mergeWaitsForPipeline(running))
    assertFalse(mergeWaitsForPipeline(mr))
    show(running)
    openDialog()
    rule.onNodeWithText("Auto-merge !12?").assertExists()
    rule.onNodeWithText("Set to auto-merge").performClick()
    assertEquals(listOf(MergeOptions(deleteSourceBranch = true, squash = false, whenPipelineSucceeds = true)), merges)
  }

  @Test
  fun `a merge request set to auto-merge offers to cancel it`() {
    show(mr.copy(mergeWhenPipelineSucceeds = true))
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("cancel_auto_merge"))
    rule.onNodeWithText("Set to auto-merge").assertExists()
    rule.onNodeWithTag("cancel_auto_merge").performClick()
    assertEquals(1, cancels)
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun capture(dark: Boolean) {
    show(mr.copy(headPipeline = GitLabPipeline(id = 900, status = "running")), dark = dark)
    openDialog()
    captureScreenRoboImage(filePath = "src/test/screenshots/merge_auto_dialog_${if (dark) "dark" else "light"}.png")
  }

  @Test fun dialog_light() = capture(dark = false)
  @Test fun dialog_dark() = capture(dark = true)
}
