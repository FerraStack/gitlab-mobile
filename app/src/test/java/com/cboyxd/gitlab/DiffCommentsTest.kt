package com.cboyxd.gitlab

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.DiffLineAnchor
import com.cboyxd.gitlab.data.model.GitLabDiffPosition
import com.cboyxd.gitlab.data.model.GitLabDiffRefs
import com.cboyxd.gitlab.data.model.GitLabDiscussion
import com.cboyxd.gitlab.data.model.GitLabNote
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.MergeRequestDetailState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Commenting on a line of a merge request's changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DiffCommentsTest {

  @get:Rule val rule = createComposeRule()
  private val posted = mutableListOf<Pair<DiffLineAnchor, String>>()

  private val refs = GitLabDiffRefs(baseSha = "base111", startSha = "start222", headSha = "head333")
  private val reviewer = GitLabUser(id = 2, username = "pat", name = "Pat Reviewer")

  /** A thread on the README's added line, on the version shown. */
  private val readmeThread = GitLabDiscussion(
    id = "t1",
    notes = listOf(
      GitLabNote(
        id = 10, body = "Say what it launches?", author = reviewer, createdAt = "1h ago", resolvable = true, resolved = false,
        position = GitLabDiffPosition(baseSha = "base111", headSha = "head333", oldPath = "README.md", newPath = "README.md", newLine = 2),
      ),
    ),
  )

  private fun state(withRefs: Boolean = true): MergeRequestDetailState {
    val base = Fakes.mergeRequestDetail()
    return base.copy(
      mergeRequest = Loadable(base.mergeRequest.data!!.copy(diffRefs = refs.takeIf { withRefs }), loaded = true),
      discussions = Loadable(listOf(readmeThread), loaded = true),
    )
  }

  private fun showFiles(state: MergeRequestDetailState = state(), canWrite: Boolean = true, dark: Boolean = true) {
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
          onCommentOnLine = { anchor, body, done -> posted += anchor to body; done(null) },
        )
      }
    }
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("2 files changed", substring = true))
    rule.onNodeWithText("2 files changed").performClick()
    // Lines are parsed off the main thread; wait for them rather than for idle.
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodesWithText("ignite()", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun addedLine() = rule.onNodeWithContentDescription("Added line 2:   ignite()", substring = true)

  @Test
  fun `tapping a line comments on it with a suggestion`() {
    showFiles()
    addedLine().performClick()
    rule.onNodeWithText("src/launch.kt · Added line 2").assertExists()
    rule.onNodeWithTag("suggest_change").performClick()
    // The title and the send button both read "Comment on line"; the button comes last.
    rule.onAllNodesWithText("Comment on line").onLast().performClick()

    assertEquals(1, posted.size)
    val (anchor, body) = posted.single()
    assertEquals(DiffLineAnchor("src/launch.kt", "src/launch.kt", oldLine = null, newLine = 2), anchor)
    assertEquals("```suggestion:-0+0\n  ignite()\n```", body)
  }

  @Test
  fun `removed lines take comments but not suggestions`() {
    showFiles()
    rule.onNodeWithContentDescription("Removed line 2:   return", substring = true).performClick()
    rule.onNodeWithText("src/launch.kt · Removed line 2").assertExists()
    rule.onNodeWithTag("suggest_change").assertDoesNotExist()
  }

  @Test
  fun `lines with threads are marked`() {
    showFiles()
    // The README's added line has a thread on this version; launch.kt's doesn't.
    rule.onNodeWithContentDescription("Added line 2: Now launches.", substring = true)
      .assert(hasContentDescription("Has comments", substring = true))
    addedLine().assert(hasContentDescription("Has comments", substring = true).not())
  }

  @Test
  fun `guests can't comment on lines`() {
    showFiles(canWrite = false)
    addedLine().assert(hasClickAction().not())
  }

  @Test
  fun `lines stay inert until the merge request's versions load`() {
    showFiles(state(withRefs = false))
    addedLine().assert(hasClickAction().not())
  }

  private fun captureFiles(dark: Boolean) {
    showFiles(dark = dark)
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/diff_commented_line_${if (dark) "dark" else "light"}.png")
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun captureSheet(dark: Boolean) {
    showFiles(dark = dark)
    addedLine().performClick()
    rule.onNodeWithTag("suggest_change").performClick()
    rule.waitForIdle()
    captureScreenRoboImage(filePath = "src/test/screenshots/diff_comment_sheet_${if (dark) "dark" else "light"}.png")
  }

  @Test fun files_light() = captureFiles(dark = false)
  @Test fun files_dark() = captureFiles(dark = true)
  @Test fun sheet_light() = captureSheet(dark = false)
  @Test fun sheet_dark() = captureSheet(dark = true)
}
