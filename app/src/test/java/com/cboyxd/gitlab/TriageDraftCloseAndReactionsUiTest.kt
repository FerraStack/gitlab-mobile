package com.cboyxd.gitlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cboyxd.gitlab.data.model.GitLabAwardEmoji
import com.cboyxd.gitlab.data.model.GitLabBlobSearchResult
import com.cboyxd.gitlab.data.model.GitLabCommit
import com.cboyxd.gitlab.data.model.GitLabIssueUpdate
import com.cboyxd.gitlab.data.model.GitLabMergeRequestUpdate
import com.cboyxd.gitlab.data.model.GitLabMilestone
import com.cboyxd.gitlab.data.model.GitLabNoteSearchResult
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.ui.components.MarkdownContext
import com.cboyxd.gitlab.ui.screens.IssueDetailScreen
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.screens.ProjectDetailScreen
import com.cboyxd.gitlab.ui.screens.SearchScreen
import com.cboyxd.gitlab.ui.screens.summarizeReactions
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.SearchScope
import com.cboyxd.gitlab.ui.viewmodel.SearchState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class TriageDraftCloseAndReactionsUiTest {

  @get:Rule val rule = createComposeRule()

  @Test
  fun `summarizeReactions uses fallback votes until awards load and marks current user's awards`() {
    val fallback = summarizeReactions(
      awards = emptyList(),
      fallbackUpvotes = 2,
      fallbackDownvotes = 1,
      awardsLoaded = false,
    )
    assertEquals(listOf("thumbsup", "thumbsdown"), fallback.map { it.name })
    assertEquals(listOf(2, 1), fallback.map { it.count })
    assertTrue(fallback.none { it.reactedByMe })

    val loaded = summarizeReactions(
      awards = listOf(
        GitLabAwardEmoji(id = 1, name = "rocket", user = GitLabUser(id = 10, username = "Tanuki")),
        GitLabAwardEmoji(id = 2, name = "rocket", user = GitLabUser(id = 11, username = "pat")),
        GitLabAwardEmoji(id = 3, name = "eyes", user = GitLabUser(id = 11, username = "pat")),
      ),
      currentUsername = "tanuki",
      currentUserId = 10L,
      awardsLoaded = true,
    )
    assertEquals(2, loaded.size)
    assertEquals(2, loaded.first { it.name == "rocket" }.count)
    assertTrue(loaded.first { it.name == "rocket" }.reactedByMe)
    assertFalse(loaded.first { it.name == "eyes" }.reactedByMe)
  }

  @Test
  fun `closing an open merge request requires confirmation before calling onSetClosed`() {
    val closedCalls = mutableListOf<Boolean>()
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        MergeRequestDetailScreen(
          state = Fakes.mergeRequestDetail(),
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onMerge = {},
          onSetClosed = { closedCalls += it },
          onComment = { _, _ -> },
          onOpenPipeline = {},
          onOpenCommit = {},
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
        )
      }
    }

    rule.onNodeWithContentDescription("More options").performClick()
    rule.onNodeWithText("Close merge request").performClick()
    rule.onNodeWithText("Close merge request !12?").assertIsDisplayed()
    assertTrue(closedCalls.isEmpty())

    rule.onNodeWithText("Cancel").performClick()
    rule.onNodeWithText("Close merge request !12?").assertDoesNotExist()
    assertTrue(closedCalls.isEmpty())

    rule.onNodeWithContentDescription("More options").performClick()
    rule.onNodeWithText("Close merge request").performClick()
    rule.onNodeWithTag("confirm_close_mr").performClick()
    assertEquals(listOf(true), closedCalls)
  }

  @Test
  fun `merge request can edit title and description and triage assignees, reviewers, labels and milestone`() {
    val updates = mutableListOf<GitLabMergeRequestUpdate>()
    val alice = GitLabUser(id = 1, username = "tanuki", name = "Tanuki Tester")
    val bob = GitLabUser(id = 2, username = "pat", name = "Pat Reviewer")
    val milestone = GitLabMilestone(id = 5, title = "v2.0")

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        MergeRequestDetailScreen(
          state = Fakes.mergeRequestDetail(),
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
          projectMembers = Loadable(listOf(alice, bob), loaded = true),
          projectMilestones = Loadable(listOf(milestone), loaded = true),
          onUpdate = { update, done ->
            updates += update
            done(null)
          },
        )
      }
    }

    rule.onNodeWithContentDescription("More options").performClick()
    rule.onNodeWithText("Edit").performClick()
    rule.onNodeWithTag("edit_mr_title").performTextReplacement("Better MR title")
    rule.onNodeWithText("Save changes").performClick()
    assertEquals(1, updates.size)
    assertEquals("Better MR title", updates[0].title)

    rule.onNodeWithText("Details").performClick()
    rule.onNodeWithTag("edit_reviewers").performClick()
    rule.onNodeWithTag("pick_member_2").performClick()
    rule.onNodeWithTag("triage_save").performClick()
    assertEquals(listOf(2L), updates[1].reviewerIds)

    rule.onNodeWithTag("edit_milestone").performClick()
    rule.onNodeWithTag("pick_milestone_5").performClick()
    rule.onNodeWithTag("triage_save").performClick()
    assertEquals(5L, updates[2].milestoneId)
  }

  @Test
  fun `issue triage updates assignees and labels`() {
    val triageCalls = mutableListOf<GitLabIssueUpdate>()
    val alice = GitLabUser(id = 1, username = "tanuki", name = "Tanuki Tester")
    val bob = GitLabUser(id = 2, username = "pat", name = "Pat Reviewer")

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        IssueDetailScreen(
          state = Fakes.issueDetail(),
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onComment = { _, _ -> },
          onSetClosed = {},
          onEdit = { _, _, _ -> },
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          projectMembers = Loadable(listOf(alice, bob), loaded = true),
          onTriage = { update, done ->
            triageCalls += update
            done(null)
          },
        )
      }
    }

    rule.onNodeWithContentDescription("Details").performClick()
    rule.onNodeWithTag("edit_assignees").performClick()
    rule.onNodeWithTag("pick_member_2").performClick()
    rule.onNodeWithTag("triage_save").performClick()
    assertEquals(listOf(2L), triageCalls[0].assigneeIds)

    rule.onNodeWithTag("edit_labels").performClick()
    rule.onNodeWithTag("pick_label_bug").performClick()
    rule.onNodeWithTag("triage_save").performClick()
    assertEquals("bug", triageCalls[1].removeLabels)
  }

  @Test
  fun `comment sheet restores saved drafts, updates on edit, and supports discard and submit clear`() {
    val submitted = mutableListOf<String>()
    var persistedDrafts = emptyMap<String, String>()

    rule.setContent {
      var drafts by remember { mutableStateOf(mapOf("issue:1:7:comment" to "Saved WIP")) }
      persistedDrafts = drafts
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        IssueDetailScreen(
          state = Fakes.issueDetail(),
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onComment = { body, done ->
            submitted += body
            done(null)
          },
          onSetClosed = {},
          onEdit = { _, _, _ -> },
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          drafts = drafts,
          onDraftChange = { k, v ->
            drafts = if (v.isEmpty()) drafts - k else drafts + (k to v)
            persistedDrafts = drafts
          },
          onClearDraft = { k ->
            drafts = drafts - k
            persistedDrafts = drafts
          },
        )
      }
    }

    rule.onNodeWithText("Comment").performClick()
    rule.onNodeWithText("Saved WIP").assertExists()
    rule.onNodeWithTag("discard_draft").performClick()
    assertFalse("issue:1:7:comment" in persistedDrafts)

    rule.onNodeWithText("Add a comment").performTextInput("Ready to post")
    assertEquals("Ready to post", persistedDrafts["issue:1:7:comment"])

    rule.onAllNodesWithText("Comment").onLast().performClick()
    assertEquals(listOf("Ready to post"), submitted)
    assertFalse("issue:1:7:comment" in persistedDrafts)
  }

  @Test
  fun `issue reactions chip and quick picker invoke toggle callback`() {
    val toggled = mutableListOf<String>()
    val state = Fakes.issueDetail().copy(
      awardEmojis = listOf(
        GitLabAwardEmoji(id = 1, name = "thumbsup", user = Fakes.user),
      ),
      awardEmojisLoaded = true,
    )

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        IssueDetailScreen(
          state = state,
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onComment = { _, _ -> },
          onSetClosed = {},
          onEdit = { _, _, _ -> },
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          currentUsername = Fakes.user.username,
          currentUserId = Fakes.user.id,
          onToggleReaction = { toggled += it },
        )
      }
    }

    rule.onNodeWithContentDescription("thumbsup: 1, reacted").performClick()
    assertEquals(listOf("thumbsup"), toggled)

    rule.onNodeWithTag("issue_add_reaction").performClick()
    rule.onNodeWithText("Rocket").performClick()
    assertEquals(listOf("thumbsup", "rocket"), toggled)
  }

  @Test
  fun `project detail search icon and project-scoped search scopes render and route results`() {
    var searchClicks = 0
    val openedBlobs = mutableListOf<GitLabBlobSearchResult>()
    val openedCommits = mutableListOf<GitLabCommit>()
    val openedNotes = mutableListOf<GitLabNoteSearchResult>()
    val blob = GitLabBlobSearchResult(
      basename = "Rocket",
      data = "class Rocket",
      path = "src/Rocket.kt",
      filename = "Rocket.kt",
      ref = "main",
      startline = 4,
      projectId = 1L,
    )
    val commit = GitLabCommit(id = "deadbeef1234", title = "Add search", authorName = "Tanuki", createdAt = "1h ago", projectId = 1L)
    val note = GitLabNoteSearchResult(
      id = 55L,
      body = "Looks good",
      author = Fakes.user,
      createdAt = "2h ago",
      noteableType = "Issue",
      noteableIid = 7L,
      projectId = 1L,
      title = "Launch button",
    )

    rule.setContent {
      var mode by remember { mutableStateOf(0) }
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        when (mode) {
          0 -> ProjectDetailScreen(
            state = Fakes.projectState(),
            pendingStar = false,
            canWrite = false,
            markdown = MarkdownContext(),
            onBack = {},
            onRetry = {},
            onToggleStar = {},
            onOpenSection = {},
            onOpenCode = {},
            onOpenRelease = {},
            onOpenInBrowser = {},
            onOpenSearch = {
              searchClicks++
              mode = 1
            },
          )
          1 -> SearchScreen(
            state = SearchState(
              query = "rocket",
              scope = SearchScope.BLOBS,
              projectId = 1L,
              projectName = "rocket",
              blobs = Loadable(listOf(blob), loaded = true),
              commits = Loadable(listOf(commit), loaded = true),
              notes = Loadable(listOf(note), loaded = true),
            ),
            isSignedIn = false,
            onBack = {},
            onQueryChange = {},
            onScopeChange = { mode = if (it == SearchScope.COMMITS) 2 else if (it == SearchScope.NOTES) 3 else 1 },
            onOpenProject = {},
            onOpenIssue = {},
            onOpenMergeRequest = {},
            onOpenUser = {},
            onOpenBlob = { openedBlobs += it },
            onOpenCommit = { openedCommits += it },
            onOpenNote = { openedNotes += it },
          )
          2 -> SearchScreen(
            state = SearchState(
              query = "rocket",
              scope = SearchScope.COMMITS,
              projectId = 1L,
              projectName = "rocket",
              commits = Loadable(listOf(commit), loaded = true),
            ),
            isSignedIn = false,
            onBack = {},
            onQueryChange = {},
            onScopeChange = { if (it == SearchScope.NOTES) mode = 3 },
            onOpenProject = {},
            onOpenIssue = {},
            onOpenMergeRequest = {},
            onOpenUser = {},
            onOpenCommit = { openedCommits += it },
          )
          else -> SearchScreen(
            state = SearchState(
              query = "rocket",
              scope = SearchScope.NOTES,
              projectId = 1L,
              projectName = "rocket",
              notes = Loadable(listOf(note), loaded = true),
            ),
            isSignedIn = false,
            onBack = {},
            onQueryChange = {},
            onScopeChange = {},
            onOpenProject = {},
            onOpenIssue = {},
            onOpenMergeRequest = {},
            onOpenUser = {},
            onOpenNote = { openedNotes += it },
          )
        }
      }
    }

    rule.onNodeWithContentDescription("Search project").performClick()
    assertEquals(1, searchClicks)

    rule.onNodeWithText("Projects").assertDoesNotExist()
    rule.onNodeWithText("Sign in to search").assertDoesNotExist()
    rule.onNodeWithText("Rocket.kt").performClick()
    assertEquals(listOf(blob), openedBlobs)

    rule.onNodeWithText("Commits").performClick()
    rule.onNodeWithText("Add search").performClick()
    assertEquals(listOf(commit), openedCommits)

    rule.onNodeWithText("Comments").performClick()
    rule.onNodeWithText("Looks good").performClick()
    assertEquals(listOf(note), openedNotes)
  }
}
