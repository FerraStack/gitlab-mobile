package com.cboyxd.gitlab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.screens.RunPipelineScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Run pipeline" page and the action that opens it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RunPipelineTest {

  @get:Rule val rule = createComposeRule()
  private val runs = mutableListOf<String>()
  private val destinations = mutableListOf<ScreenDestination>()

  /** A focused text field blinks its cursor forever; advance the clock by hand so tests can settle. */
  @Before fun manualClock() {
    rule.mainClock.autoAdvance = false
  }

  private fun settle() = rule.mainClock.advanceTimeBy(600)

  private fun showSection(canWrite: Boolean = true) = rule.setContent {
    GitLabTheme(darkTheme = true, reducedMotion = true) {
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
        onNavigate = { destinations += it },
        onOpenInBrowser = {},
      )
    }
  }

  private fun showForm(dark: Boolean = true, answer: String? = null) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      RunPipelineScreen(
        projectPath = "example-org/rocket",
        defaultRef = "main",
        onRun = { ref, done -> runs += ref; done(answer) },
        onBack = {},
      )
    }
  }

  @Test
  fun `the pipelines list opens the run pipeline page`() {
    showSection()
    settle()
    rule.onNodeWithTag("run_pipeline").performClick()
    assertEquals(listOf<ScreenDestination>(ScreenDestination.RunPipeline(1)), destinations)
  }

  @Test
  fun `guests can't run pipelines`() {
    showSection(canWrite = false)
    settle()
    rule.onNodeWithTag("run_pipeline").assertDoesNotExist()
  }

  @Test
  fun `the form starts on the current branch and runs the chosen ref`() {
    showForm()
    settle()
    rule.onNodeWithText("main").assertExists()
    rule.onNodeWithTag("pipeline_ref").performTextReplacement("release/1.5")
    settle()
    rule.onNodeWithTag("run_pipeline_confirm").performClick()
    settle()
    assertEquals(listOf("release/1.5"), runs)
  }

  @Test
  fun `a refusal is shown on the form`() {
    showForm(answer = "Reference not found")
    settle()
    rule.onNodeWithTag("run_pipeline_confirm").performClick()
    settle()
    rule.onNodeWithText("Reference not found").assertExists()
  }

  private fun capture(dark: Boolean) {
    showForm(dark = dark)
    settle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/run_pipeline_${if (dark) "dark" else "light"}.png")
  }

  @Test fun form_light() = capture(dark = false)
  @Test fun form_dark() = capture(dark = true)
}
