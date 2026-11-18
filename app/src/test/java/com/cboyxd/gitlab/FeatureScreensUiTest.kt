package com.cboyxd.gitlab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabArtifactsFile
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.repository.JobTrace
import com.cboyxd.gitlab.ui.screens.InboxScreen
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.screens.PipelineDetailScreen
import com.cboyxd.gitlab.ui.screens.SearchScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.GitLabNotification
import com.cboyxd.gitlab.ui.viewmodel.InboxFilter
import com.cboyxd.gitlab.ui.viewmodel.JobDetailState
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.NotificationType
import com.cboyxd.gitlab.ui.viewmodel.PipelineDetailState
import com.cboyxd.gitlab.ui.viewmodel.SearchScope
import com.cboyxd.gitlab.ui.viewmodel.SearchState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI behavior tests for InboxScreen, PipelineDetailScreen, JobDetailScreen, and SearchScreen (#62). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FeatureScreensUiTest {

  @get:Rule val rule = createComposeRule()

  private val sampleNotifications = listOf(
    GitLabNotification(
      id = "1",
      type = NotificationType.MERGE_REQUEST,
      title = "!12 Fix crash",
      projectPath = "example-org/rocket",
      authorName = "Alice",
      authorUsername = "alice",
      authorAvatarUrl = null,
      timestamp = "5m ago",
      isUnread = true,
      actionName = "assigned",
      targetType = "MergeRequest",
      targetProjectId = 1,
      targetIid = 12,
      targetUrl = null,
      statusBadge = "Assigned",
    ),
    GitLabNotification(
      id = "2",
      type = NotificationType.MENTION,
      title = "#7 Tablet UI",
      projectPath = "example-org/rocket",
      authorName = "Bob",
      authorUsername = "bob",
      authorAvatarUrl = null,
      timestamp = "1h ago",
      isUnread = false,
      actionName = "mentioned",
      targetType = "Issue",
      targetProjectId = 1,
      targetIid = 7,
      targetUrl = null,
      statusBadge = "Mentioned",
    ),
  )

  @Test
  fun `InboxScreen shows sign-in prompt to guests`() {
    var signInClicks = 0
    rule.setContent {
      GitLabTheme(darkTheme = false, reducedMotion = true) {
        InboxScreen(
          state = Fakes.guestState().copy(activeInstance = GitLabAuth.GUEST),
          onRefresh = {},
          onFilterChange = {},
          onOpen = {},
          onMarkDone = {},
          onMarkAllDone = {},
          onAddServer = { signInClicks++ },
        )
      }
    }
    rule.onNodeWithText("Sign in to see your inbox").assertIsDisplayed()
    rule.onNodeWithText("Sign in").performClick()
    assertEquals(1, signInClicks)
  }

  @Test
  fun `InboxScreen handles filter, mark-done, and mark-all when signed in`() {
    val selectedFilters = mutableListOf<InboxFilter>()
    val doneIds = mutableListOf<String>()
    var markAllCount = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        InboxScreen(
          state = Fakes.signedInState().copy(
            notifications = Loadable(sampleNotifications, loaded = true),
            inboxFilter = InboxFilter.ALL,
          ),
          onRefresh = {},
          onFilterChange = { selectedFilters += it },
          onOpen = {},
          onMarkDone = { doneIds += it },
          onMarkAllDone = { markAllCount++ },
          onAddServer = {},
        )
      }
    }

    rule.onNodeWithText("!12 Fix crash").assertIsDisplayed()
    rule.onNodeWithText("#7 Tablet UI").assertIsDisplayed()
    rule.onNodeWithText("1 to-do").assertIsDisplayed()

    rule.onNodeWithContentDescription("Mark as done").performClick()
    assertEquals(listOf("1"), doneIds)

    rule.onNodeWithContentDescription("Mark all as done").assertIsEnabled().performClick()
    rule.onNodeWithText("Mark all done").performClick()
    assertEquals(1, markAllCount)

    rule.onNodeWithContentDescription("Show: All").performClick()
    rule.onNodeWithText("Mentioned").performClick()
    assertEquals(listOf(InboxFilter.MENTIONED), selectedFilters)
  }

  @Test
  fun `InboxScreen UNREAD filter hides done items and disables mark-all when 0 unread`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        InboxScreen(
          state = Fakes.signedInState().copy(
            notifications = Loadable(sampleNotifications.map { it.copy(isUnread = false) }, loaded = true),
            inboxFilter = InboxFilter.UNREAD,
          ),
          onRefresh = {},
          onFilterChange = {},
          onOpen = {},
          onMarkDone = {},
          onMarkAllDone = {},
          onAddServer = {},
        )
      }
    }
    rule.onNodeWithContentDescription("Mark all as done").assertIsNotEnabled()
    rule.onNodeWithText("Nothing needs your attention right now.").assertIsDisplayed()
  }

  @Test
  fun `PipelineDetailScreen shows running indicator, stage headers, and cancel dialog`() {
    var canceled = 0
    val openedJobs = mutableListOf<Long>()
    val playedJobs = mutableListOf<Long>()

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        PipelineDetailScreen(
          state = PipelineDetailState(
            projectId = 1,
            pipelineId = 50,
            pipeline = Loadable(GitLabPipeline(id = 50, projectId = 1, status = "running", ref = "main", sha = "12345678abcd"), loaded = true),
            jobs = Loadable(
              listOf(
                GitLabJob(id = 1, name = "unit-test", stage = "test", status = "success"),
                GitLabJob(id = 2, name = "deploy-prod", stage = "deploy", status = "manual"),
              ),
              loaded = true,
            ),
          ),
          canWrite = true,
          onBack = {},
          onRetryLoad = {},
          onRetryPipeline = {},
          onCancel = { canceled++ },
          onOpenJob = { openedJobs += it.id },
          onOpenCommit = {},
          onOpenInBrowser = {},
          onPlayJob = { playedJobs += it.id },
        )
      }
    }

    rule.onNodeWithContentDescription("Refreshes while running").assertExists()
    rule.onNodeWithText("Test").assertIsDisplayed()
    rule.onNodeWithText("Deploy").assertIsDisplayed()

    rule.onNodeWithText("unit-test").performClick()
    assertEquals(listOf(1L), openedJobs)

    rule.onNodeWithTag("play_2").performClick()
    assertEquals(listOf(2L), playedJobs)

    rule.onNodeWithText("Cancel").performClick()
    rule.onNodeWithText("Cancel pipeline #50?").assertIsDisplayed()
    rule.onNodeWithText("Cancel pipeline").performClick()
    assertEquals(1, canceled)
  }

  @Test
  fun `PipelineDetailScreen shows retry action on failed pipeline`() {
    var retried = 0
    rule.setContent {
      GitLabTheme(darkTheme = false, reducedMotion = true) {
        PipelineDetailScreen(
          state = PipelineDetailState(
            projectId = 1,
            pipelineId = 51,
            pipeline = Loadable(GitLabPipeline(id = 51, projectId = 1, status = "failed", ref = "main"), loaded = true),
            jobs = Loadable(emptyList(), loaded = true),
          ),
          canWrite = true,
          onBack = {},
          onRetryLoad = {},
          onRetryPipeline = { retried++ },
          onCancel = {},
          onOpenJob = {},
          onOpenCommit = {},
          onOpenInBrowser = {},
          onPlayJob = {},
        )
      }
    }
    rule.onNodeWithText("Retry").performClick()
    assertEquals(1, retried)
  }

  @Test
  fun `JobDetailScreen shows failure reason, retry, artifacts download, and log lines`() {
    var retried = 0
    var downloadedName: String? = null
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        JobDetailScreen(
          state = JobDetailState(
            projectId = 1,
            jobId = 88,
            job = Loadable(
              GitLabJob(
                id = 88,
                name = "build-apk",
                stage = "build",
                status = "failed",
                failureReason = "script_failure",
                artifactsFile = GitLabArtifactsFile("artifacts.zip", 2048L),
              ),
              loaded = true,
            ),
            trace = Loadable(JobTrace("Running gradle...\nBUILD FAILED\n", truncated = false), loaded = true),
          ),
          canWrite = true,
          onBack = {},
          onRetryLoad = {},
          onRetryJob = { retried++ },
          onOpenInBrowser = {},
          onPlayJob = {},
          onCancelJob = {},
          onDownloadArtifacts = { downloadedName = it },
        )
      }
    }

    rule.onNodeWithText("The job script exited with an error. Check the log below.").assertIsDisplayed()
    rule.onNodeWithText("Retry job").performClick()
    assertEquals(1, retried)

    rule.onNodeWithTag("download_artifacts").performClick()
    assertEquals("build-apk-88-artifacts.zip", downloadedName)

    rule.onNodeWithText("BUILD FAILED", substring = true).assertIsDisplayed()
  }

  @Test
  fun `SearchScreen handles short queries and scope change`() {
    val scopes = mutableListOf<SearchScope>()

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SearchScreen(
          state = SearchState(query = "a", scope = SearchScope.PROJECTS),
          isSignedIn = false,
          onBack = {},
          onQueryChange = {},
          onScopeChange = { scopes += it },
          onOpenProject = {},
          onOpenIssue = {},
          onOpenMergeRequest = {},
          onOpenUser = {},
        )
      }
    }
    rule.onNodeWithText("Find projects, issues, merge requests and people.").assertIsDisplayed()

    rule.onNodeWithText("Issues").performClick()
    assertEquals(listOf(SearchScope.ISSUES), scopes)
  }

  @Test
  fun `SearchScreen shows guest sign-in lock for issues scope`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SearchScreen(
          state = SearchState(query = "rocket", scope = SearchScope.ISSUES),
          isSignedIn = false,
          onBack = {},
          onQueryChange = {},
          onScopeChange = {},
          onOpenProject = {},
          onOpenIssue = {},
          onOpenMergeRequest = {},
          onOpenUser = {},
        )
      }
    }
    rule.onNodeWithText("Sign in to search issues").assertIsDisplayed()
  }

  @Test
  fun `SearchScreen opens clicked project from results`() {
    val openedProjects = mutableListOf<Long>()
    rule.setContent {
      GitLabTheme(darkTheme = false, reducedMotion = true) {
        SearchScreen(
          state = SearchState(
            query = "rocket",
            scope = SearchScope.PROJECTS,
            projects = Loadable(listOf(Fakes.projects.first()), loaded = true),
          ),
          isSignedIn = true,
          onBack = {},
          onQueryChange = {},
          onScopeChange = {},
          onOpenProject = { openedProjects += it },
          onOpenIssue = {},
          onOpenMergeRequest = {},
          onOpenUser = {},
        )
      }
    }
    rule.onAllNodesWithText("rocket").onLast().performClick()
    assertEquals(listOf(1L), openedProjects)
  }
}
