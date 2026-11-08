package com.cboyxd.gitlab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.cboyxd.gitlab.data.model.GitLabContributor
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ContributorsListTest {

  @get:Rule val rule = createComposeRule()

  @Test
  fun `contributors whose names and commit counts repeat are all listed`() {
    val people = listOf(
      GitLabContributor(name = "Alice", commits = 12, email = "alice@work.example"),
      GitLabContributor(name = "Alice", commits = 12, email = "alice@home.example"),
      GitLabContributor(name = "Alice1", commits = 2, email = "alice1@example.com"),
    )
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        ProjectSectionScreen(
          section = ProjectSection.CONTRIBUTORS,
          state = Fakes.projectState().copy(contributors = Loadable(people, loaded = true)),
          labels = emptyMap(),
          canWrite = false,
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
    // Before the fix, composing this list threw "Key … was already used".
    assertEquals(2, rule.onAllNodesWithText("Alice", substring = false).fetchSemanticsNodes().size)
    assertEquals(1, rule.onAllNodesWithText("Alice1").fetchSemanticsNodes().size)
  }
}
