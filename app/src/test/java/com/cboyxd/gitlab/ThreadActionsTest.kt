package com.cboyxd.gitlab

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabDiscussion
import com.cboyxd.gitlab.data.model.GitLabNote
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.data.model.resolved
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reply to and resolve merge request threads. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ThreadActionsTest {

  @get:Rule val rule = createComposeRule()
  private val resolves = mutableListOf<Pair<String, Boolean>>()
  private val replies = mutableListOf<Pair<String, String>>()

  private val reviewer = GitLabUser(id = 2, username = "pat", name = "Pat Reviewer")

  private fun thread(id: String, resolved: Boolean) = GitLabDiscussion(
    id = id,
    notes = listOf(
      GitLabNote(id = 10, body = "Should this handle tablets in landscape too?", author = reviewer, createdAt = "2h ago", resolvable = true, resolved = resolved),
      GitLabNote(id = 11, body = "Yes, added in the last commit.", author = Fakes.user, createdAt = "1h ago", resolvable = true, resolved = resolved),
    ),
  )

  private fun show(canWrite: Boolean = true, dark: Boolean = true, resolved: Boolean = false) {
    val state = Fakes.mergeRequestDetail().copy(discussions = Loadable(listOf(thread("t1", resolved)), loaded = true))
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
          onReply = { id, body, done -> replies += id to body; done(null) },
          onSetResolved = { id, value -> resolves += id to value },
        )
      }
    }
  }

  private fun scrollTo(tag: String) = rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

  @Test
  fun `a thread can be resolved and reopened`() {
    show()
    scrollTo("resolve_t1")
    rule.onNodeWithText("Resolve thread").assertExists()
    rule.onNodeWithTag("resolve_t1").performClick()
    assertEquals(listOf("t1" to true), resolves)
  }

  @Test
  fun `a resolved thread says so and offers to reopen`() {
    show(resolved = true)
    scrollTo("resolve_t1")
    assertTrue(rule.onAllNodesWithText("Resolved").fetchSemanticsNodes().isNotEmpty())
    rule.onNodeWithText("Unresolve").assertExists()
    rule.onNodeWithTag("resolve_t1").performClick()
    assertEquals(listOf("t1" to false), resolves)
  }

  @Test
  fun `reply opens a sheet for that thread`() {
    show()
    scrollTo("reply_t1")
    val before = rule.onAllNodesWithText("Reply").fetchSemanticsNodes().size
    rule.onNodeWithTag("reply_t1").performClick()
    rule.waitForIdle()
    // The sheet adds its "Reply" title and send button.
    assertTrue(rule.onAllNodesWithText("Reply").fetchSemanticsNodes().size > before)
  }

  @Test
  fun `guests see no thread actions`() {
    show(canWrite = false)
    rule.onNodeWithTag("reply_t1").assertDoesNotExist()
    rule.onNodeWithTag("resolve_t1").assertDoesNotExist()
  }

  @Test
  fun `resolution follows the resolvable notes`() {
    assertTrue(thread("a", resolved = true).resolved)
    assertFalse(thread("b", resolved = false).resolved)
    assertFalse(GitLabDiscussion("c", individualNote = true, notes = listOf(GitLabNote(id = 1, body = "hi"))).resolved)
  }

  private fun capture(name: String, dark: Boolean) {
    show(dark = dark)
    scrollTo("resolve_t1")
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Test fun thread_light() = capture("merge_request_thread_actions", dark = false)
  @Test fun thread_dark() = capture("merge_request_thread_actions", dark = true)
}
