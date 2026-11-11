package com.cboyxd.gitlab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ProjectDetailState
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A project's issue list follows the server's state filter, counts and pages. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ProjectListsTest {

  @get:Rule val rule = createComposeRule()

  private val states = mutableListOf<String>()
  private val more = mutableListOf<ProjectSection>()

  private fun state(failed: Boolean = false): ProjectDetailState = Fakes.projectState().copy(
    issues = Loadable(Fakes.issues.filter { it.state == "opened" }, loaded = true),
    issueState = "opened",
    issueCounts = mapOf("opened" to 4012, "closed" to 988, "all" to 5000),
    issuesNextPage = 2,
    loadMoreFailed = failed,
  )

  private fun show(dark: Boolean = true, failed: Boolean = false) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      ProjectSectionScreen(
        section = ProjectSection.ISSUES,
        state = state(failed),
        labels = Fakes.labels,
        canWrite = true,
        onBack = {},
        onRetry = {},
        onOpenIssue = {},
        onOpenMergeRequest = {},
        onCreateMergeRequest = { _, _, _, _, _, _ -> },
        onLoadBranches = { _, _ -> Result.success(emptyList()) },
        onOpenPipeline = {},
        onRetryPipeline = {},
        onOpenCommit = {},
        onBrowseRef = {},
        onOpenRelease = {},
        onNavigate = {},
        onOpenInBrowser = {},
        onSetIssueState = { states += it },
        onLoadMore = { more += it },
      )
    }
  }

  @Test fun issues_paged_light() { show(dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/project_issues_paged_light.png") }
  @Test fun issues_paged_dark() { show(dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/project_issues_paged_dark.png") }

  @Test
  fun `the state filter shows the project's exact counts and asks the server`() {
    show()
    rule.onNodeWithContentDescription("State: Open").performClick()
    rule.onNodeWithText("4012").assertExists()
    rule.onNodeWithText("988").assertExists()
    rule.onNodeWithText("Closed").performClick()
    assertEquals(listOf("closed"), states)
  }

  @Test
  fun `reaching the end of the list loads the next page by itself`() {
    show()
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("load_more"))
    rule.waitForIdle()
    assertEquals(listOf(ProjectSection.ISSUES), more)
  }

  @Test
  fun `after a failed page the list waits for a tap`() {
    show(failed = true)
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("load_more"))
    rule.waitForIdle()
    assertEquals(emptyList<ProjectSection>(), more)
    rule.onNodeWithText("Try again").performClick()
    assertEquals(listOf(ProjectSection.ISSUES), more)
  }
}
