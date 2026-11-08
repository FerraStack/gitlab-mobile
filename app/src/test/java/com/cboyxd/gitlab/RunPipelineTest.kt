package com.cboyxd.gitlab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.screens.RunPipelineDialog
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Running a new pipeline from a project's pipeline list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RunPipelineTest {

  @get:Rule val rule = createComposeRule()
  private val runs = mutableListOf<String>()

  private fun showSection(canWrite: Boolean = true, dark: Boolean = true, answer: String? = null) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      ProjectSectionScreen(
        section = ProjectSection.PIPELINES,
        state = Fakes.projectState().copy(pipelines = Loadable(emptyList(), loaded = true)),
        labels = emptyMap(),
        canWrite = canWrite,
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
        onRunPipeline = { ref, done -> runs += ref; done(answer) },
      )
    }
  }

  @Test
  fun `run pipeline asks for a ref, prefilled with the current branch`() {
    showSection()
    rule.onNodeWithTag("run_pipeline").performClick()
    rule.onNodeWithText("main").assertExists()
    rule.onNodeWithTag("pipeline_ref").performTextReplacement("release/1.5")
    rule.onNodeWithTag("run_pipeline_confirm").performClick()
    assertEquals(listOf("release/1.5"), runs)
    rule.onNodeWithTag("pipeline_ref").assertDoesNotExist()
  }

  @Test
  fun `a refusal stays in the dialog`() {
    showSection(answer = "Reference not found")
    rule.onNodeWithTag("run_pipeline").performClick()
    rule.onNodeWithTag("run_pipeline_confirm").performClick()
    rule.onNodeWithText("Reference not found").assertExists()
    rule.onNodeWithTag("pipeline_ref").assertExists()
  }

  @Test
  fun `guests can't run pipelines`() {
    showSection(canWrite = false)
    rule.onNodeWithTag("run_pipeline").assertDoesNotExist()
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun capture(dark: Boolean) {
    rule.setContent {
      GitLabTheme(darkTheme = dark, reducedMotion = true) { RunPipelineDialog(defaultRef = "main", onRun = { _, _ -> }, onDismiss = {}) }
    }
    rule.waitForIdle()
    captureScreenRoboImage(filePath = "src/test/screenshots/run_pipeline_${if (dark) "dark" else "light"}.png")
  }

  @Test fun dialog_light() = capture(dark = false)
  @Test fun dialog_dark() = capture(dark = true)
}
