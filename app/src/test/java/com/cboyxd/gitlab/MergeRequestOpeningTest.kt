package com.cboyxd.gitlab

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabApprovals
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.MergeRequestDetailState
import com.cboyxd.gitlab.ui.viewmodel.emptyFeed
import com.cboyxd.gitlab.ui.viewmodel.mergeRequestReadyToOpen
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A merge request opens on its summary; the rest loads on the page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class MergeRequestOpeningTest {

  @get:Rule val rule = createComposeRule()

  /** Summary known (from a list), everything else still loading. */
  private fun loading() = MergeRequestDetailState(
    projectId = 1,
    iid = 12,
    mergeRequest = Loadable<GitLabMergeRequest?>(Fakes.mergeRequests.first(), loaded = true).start(),
    diffs = emptyFeed<com.cboyxd.gitlab.data.model.GitLabFileDiff>().start(),
    notes = emptyFeed<com.cboyxd.gitlab.data.model.GitLabNote>().start(),
    discussions = emptyFeed<com.cboyxd.gitlab.data.model.GitLabDiscussion>().start(),
    commits = emptyFeed<com.cboyxd.gitlab.data.model.GitLabCommit>().start(),
    pipelines = emptyFeed<com.cboyxd.gitlab.data.model.GitLabPipeline>().start(),
    approvals = Loadable<GitLabApprovals?>(null).start(),
  )

  @Test
  fun `opens on a known summary without waiting for changes and threads`() {
    assertTrue(mergeRequestReadyToOpen(loading()))
  }

  @Test
  fun `waits for the summary when nothing is known yet, and opens on its error`() {
    val unknown = loading().copy(mergeRequest = Loadable<GitLabMergeRequest?>(null).start())
    assertFalse(mergeRequestReadyToOpen(unknown))
    assertTrue(mergeRequestReadyToOpen(unknown.copy(mergeRequest = unknown.mergeRequest.failure("404 Not Found"))))
    assertTrue(mergeRequestReadyToOpen(unknown.copy(mergeRequest = unknown.mergeRequest.success(Fakes.mergeRequests.first()))))
  }

  private fun show(dark: Boolean) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      MergeRequestDetailScreen(
        state = loading(),
        labels = Fakes.labels,
        canWrite = true,
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
      )
    }
  }

  @Test fun loading_light() { show(dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/merge_request_loading_light.png") }
  @Test fun loading_dark() { show(dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/merge_request_loading_dark.png") }

  @Test
  fun `sections that are still loading say so instead of showing empty results`() {
    show(dark = true)
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Checking approvals", substring = true))
    rule.onNodeWithText("Checking approvals", substring = true).assertExists()
    rule.onNodeWithText("0 files changed", substring = true).assertDoesNotExist()
  }
}
