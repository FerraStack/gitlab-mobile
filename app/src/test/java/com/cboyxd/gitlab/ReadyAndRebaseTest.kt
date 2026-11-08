package com.cboyxd.gitlab

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Mark as ready" for drafts and "Rebase" when the source branch is behind. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ReadyAndRebaseTest {

  @get:Rule val rule = createComposeRule()
  private var ready = 0
  private var rebases = 0

  private fun show(mr: GitLabMergeRequest, dark: Boolean = true, canWrite: Boolean = true) {
    val base = Fakes.mergeRequestDetail()
    rule.setContent {
      GitLabTheme(darkTheme = dark, reducedMotion = true) {
        MergeRequestDetailScreen(
          state = base.copy(mergeRequest = base.mergeRequest.copy(data = mr)),
          labels = Fakes.labels,
          canWrite = canWrite,
          onLink = { false },
          onRetry = {},
          onMerge = {},
          onSetClosed = {},
          onComment = { _, _ -> },
          onOpenPipeline = {},
          onOpenCommit = {},
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          onMarkReady = { ready++ },
          onRebase = { rebases++ },
        )
      }
    }
  }

  private val draft = Fakes.mergeRequests.first().copy(title = "Draft: Fix the launch button on tablets", draft = true)
  private val behind = Fakes.mergeRequests.first().copy(detailedMergeStatus = "need_rebase")

  private fun scrollTo(tag: String) = rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

  @Test
  fun `a draft can be marked as ready`() {
    show(draft)
    scrollTo("mark_ready")
    rule.onNodeWithTag("mark_ready").performClick()
    assertEquals(1, ready)
    rule.onNodeWithTag("rebase").assertDoesNotExist()
  }

  @Test
  fun `a branch behind its target can be rebased`() {
    show(behind)
    scrollTo("rebase")
    rule.onNodeWithTag("rebase").performClick()
    assertEquals(1, rebases)
    rule.onNodeWithTag("mark_ready").assertDoesNotExist()
  }

  @Test
  fun `guests get neither`() {
    show(draft.copy(detailedMergeStatus = "need_rebase"), canWrite = false)
    rule.onNodeWithTag("mark_ready").assertDoesNotExist()
    rule.onNodeWithTag("rebase").assertDoesNotExist()
  }

  private fun capture(dark: Boolean) {
    show(draft.copy(detailedMergeStatus = "need_rebase"), dark = dark)
    scrollTo("rebase")
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/merge_request_ready_rebase_${if (dark) "dark" else "light"}.png")
  }

  @Test fun blocked_light() = capture(dark = false)
  @Test fun blocked_dark() = capture(dark = true)
}
