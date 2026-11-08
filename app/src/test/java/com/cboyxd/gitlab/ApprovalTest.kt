package com.cboyxd.gitlab

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabApprovalUser
import com.cboyxd.gitlab.data.model.GitLabApprovals
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Approve and revoke from the merge request's Status section. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ApprovalTest {

  @get:Rule val rule = createComposeRule()
  private val calls = mutableListOf<Boolean>()

  private fun show(approvedByMe: Boolean, canWrite: Boolean = true, dark: Boolean = true) {
    val approvers = if (approvedByMe) listOf(GitLabApprovalUser(Fakes.user)) else emptyList()
    val state = Fakes.mergeRequestDetail().copy(
      approvals = Loadable(GitLabApprovals(approved = approvedByMe, approvalsRequired = 1, approvalsLeft = if (approvedByMe) 0 else 1, approvedBy = approvers), loaded = true),
    )
    rule.setContent {
      GitLabTheme(darkTheme = dark, reducedMotion = true) {
        MergeRequestDetailScreen(
          state = state,
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
          currentUsername = Fakes.user.username,
          onSetApproved = { calls += it },
        )
      }
    }
  }

  private fun scrollTo(tag: String) = rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

  @Test
  fun `a reviewer can approve`() {
    show(approvedByMe = false)
    scrollTo("approve")
    rule.onNodeWithTag("approve").performClick()
    assertEquals(listOf(true), calls)
  }

  @Test
  fun `an approval can be revoked`() {
    show(approvedByMe = true)
    scrollTo("revoke_approval")
    rule.onNodeWithTag("revoke_approval").performClick()
    assertEquals(listOf(false), calls)
  }

  @Test
  fun `guests can't approve`() {
    show(approvedByMe = false, canWrite = false)
    rule.onNodeWithTag("approve").assertDoesNotExist()
  }

  private fun capture(name: String, dark: Boolean) {
    show(approvedByMe = false, dark = dark)
    scrollTo("approve")
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Test fun approve_light() = capture("merge_request_approve", dark = false)
  @Test fun approve_dark() = capture("merge_request_approve", dark = true)
}
