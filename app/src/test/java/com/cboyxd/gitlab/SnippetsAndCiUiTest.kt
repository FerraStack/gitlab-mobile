package com.cboyxd.gitlab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabCiVariable
import com.cboyxd.gitlab.data.model.GitLabDeployment
import com.cboyxd.gitlab.data.model.GitLabEnvironment
import com.cboyxd.gitlab.data.model.GitLabSnippet
import com.cboyxd.gitlab.data.model.GitLabSnippetFile
import com.cboyxd.gitlab.ui.components.MarkdownContext
import com.cboyxd.gitlab.ui.screens.ProjectDetailScreen
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.screens.SnippetDetailScreen
import com.cboyxd.gitlab.ui.screens.SnippetsScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.cboyxd.gitlab.ui.viewmodel.SnippetDetailState
import com.cboyxd.gitlab.ui.viewmodel.SnippetsState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SnippetsAndCiUiTest {

  @get:Rule val rule = createComposeRule()

  @Test
  fun `snippets screen lists snippets and invokes selection`() {
    val selected = mutableListOf<GitLabSnippet>()
    val snippet = GitLabSnippet(
      id = 11L,
      title = "Release helper",
      fileName = "release.sh",
      visibility = "private",
      updatedAt = "2h ago",
      author = Fakes.user,
    )
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SnippetsScreen(
          state = SnippetsState(projectId = null, items = Loadable(listOf(snippet), loaded = true)),
          projectTitle = null,
          onBack = {},
          onRetry = {},
          onSelectSnippet = { selected += it },
        )
      }
    }

    rule.onNodeWithText("Snippets").assertIsDisplayed()
    rule.onNodeWithText("Release helper").assertIsDisplayed()
    rule.onNodeWithText("Private").assertIsDisplayed()
    rule.onNodeWithText("Release helper").performClick()
    assertEquals(listOf(11L), selected.map { it.id })
  }

  @Test
  fun `snippet detail renders metadata and code`() {
    val snippet = GitLabSnippet(
      id = 11L,
      title = "Release helper",
      fileName = "release.sh",
      description = "Tags the release",
      visibility = "public",
      updatedAt = "2h ago",
      webUrl = "https://gitlab.com/-/snippets/11",
      author = Fakes.user,
      files = listOf(GitLabSnippetFile("release.sh")),
    )
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SnippetDetailScreen(
          state = SnippetDetailState(
            snippetId = 11L,
            snippet = Loadable(snippet, loaded = true),
            rawContent = Loadable("echo releasing", loaded = true),
          ),
          onBack = {},
          onRetry = {},
          onOpenInBrowser = {},
        )
      }
    }

    rule.onNodeWithText("Release helper").assertIsDisplayed()
    rule.onNodeWithText("Tags the release").assertIsDisplayed()
    rule.onNodeWithTag("snippet_code_view").assertIsDisplayed()
    rule.onNodeWithText("echo releasing", substring = true).assertExists()
  }

  @Test
  fun `ci variables screen never exposes masked or hidden values and reveals plain values on tap`() {
    val vars = listOf(
      GitLabCiVariable(
        key = "API_SECRET",
        value = "super-secret-token-value",
        masked = true,
        protected = true,
        environmentScope = "production",
      ),
      GitLabCiVariable(
        key = "HIDDEN_KEY",
        value = "hidden-secret-value",
        hidden = true,
      ),
      GitLabCiVariable(
        key = "DEPLOY_REGION",
        value = "eu-central-1",
        masked = false,
        hidden = false,
      ),
    )

    val state = Fakes.projectState().copy(ciVariables = Loadable(vars, loaded = true))
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        ProjectSectionScreen(
          section = ProjectSection.CI_VARIABLES,
          state = state,
          labels = emptyMap(),
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
        )
      }
    }

    rule.onNodeWithText("API_SECRET").assertIsDisplayed()
    rule.onNodeWithText("HIDDEN_KEY").assertIsDisplayed()
    rule.onNodeWithText("DEPLOY_REGION").assertIsDisplayed()

    // Masked/hidden values must never be shown, and plain values start obfuscated.
    rule.onNodeWithText("super-secret-token-value").assertDoesNotExist()
    rule.onNodeWithText("hidden-secret-value").assertDoesNotExist()
    rule.onNodeWithText("eu-central-1").assertDoesNotExist()

    // Only DEPLOY_REGION gets a reveal button (not masked/hidden rows).
    assertEquals(1, rule.onAllNodesWithContentDescription("Show value").fetchSemanticsNodes().size)
    rule.onNodeWithContentDescription("Show value").performClick()
    rule.onNodeWithText("eu-central-1").assertIsDisplayed()
  }

  @Test
  fun `environments screen displays status, deployment details and opens external url`() {
    val openedUrls = mutableListOf<String>()
    val env = GitLabEnvironment(
      id = 9L,
      name = "production",
      state = "available",
      tier = "production",
      externalUrl = "https://prod.example.com",
      lastDeployment = GitLabDeployment(
        id = 100L,
        iid = 22L,
        ref = "main",
        sha = "12345678abcdef",
        status = "success",
        updatedAt = "10m ago",
        user = Fakes.user,
      ),
    )

    val state = Fakes.projectState().copy(environments = Loadable(listOf(env), loaded = true))
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        ProjectSectionScreen(
          section = ProjectSection.ENVIRONMENTS,
          state = state,
          labels = emptyMap(),
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
          onOpenInBrowser = { openedUrls += it },
        )
      }
    }

    rule.onNodeWithText("production").assertIsDisplayed()
    rule.onNodeWithText("Available").assertIsDisplayed()
    rule.onNodeWithText("main").assertIsDisplayed()
    rule.onNodeWithText("12345678").assertIsDisplayed()
    rule.onNodeWithText("Deployment #22", substring = true).assertIsDisplayed()
    rule.onNodeWithContentDescription("Open external URL").performClick()
    assertEquals(listOf("https://prod.example.com"), openedUrls)
  }

  @Test
  fun `project detail exposes environments, snippets, and write-gated ci variables`() {
    val sections = mutableListOf<ProjectSection>()
    var snippetsOpened = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        ProjectDetailScreen(
          state = Fakes.projectState(),
          pendingStar = false,
          canWrite = true,
          markdown = MarkdownContext(),
          onBack = {},
          onRetry = {},
          onToggleStar = {},
          onOpenSection = { sections += it },
          onOpenCode = {},
          onOpenRelease = {},
          onOpenInBrowser = {},
          onOpenSnippets = { snippetsOpened++ },
        )
      }
    }

    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Environments"))
    rule.onNodeWithText("Environments").performClick()
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("CI/CD variables"))
    rule.onNodeWithText("CI/CD variables").performClick()
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Snippets"))
    rule.onNodeWithText("Snippets").performClick()

    assertEquals(listOf(ProjectSection.ENVIRONMENTS, ProjectSection.CI_VARIABLES), sections)
    assertEquals(1, snippetsOpened)
  }
}
