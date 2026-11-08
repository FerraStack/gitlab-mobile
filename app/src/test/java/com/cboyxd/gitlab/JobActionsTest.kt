package com.cboyxd.gitlab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.repository.JobTrace
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.screens.PipelineDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.JobDetailState
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.PipelineDetailState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Running manual jobs and canceling running ones. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class JobActionsTest {

  @get:Rule val rule = createComposeRule()
  private val played = mutableListOf<Long>()
  private var canceled = 0

  private val jobs = listOf(
    GitLabJob(id = 40, name = "build", stage = "build", status = "success", duration = 74.0),
    GitLabJob(id = 41, name = "test", stage = "test", status = "running"),
    GitLabJob(id = 42, name = "deploy-staging", stage = "deploy", status = "manual"),
  )

  private fun showPipeline(dark: Boolean = true, canWrite: Boolean = true) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      PipelineDetailScreen(
        state = PipelineDetailState(
          projectId = 1,
          pipelineId = 900,
          pipeline = Loadable(GitLabPipeline(id = 900, projectId = 1, status = "running", ref = "main", sha = "abc1234def"), loaded = true),
          jobs = Loadable(jobs, loaded = true),
        ),
        canWrite = canWrite,
        onBack = {},
        onRetryLoad = {},
        onRetryPipeline = {},
        onCancel = {},
        onOpenJob = {},
        onOpenCommit = {},
        onOpenInBrowser = {},
        onPlayJob = { played += it.id },
      )
    }
  }

  private fun showJob(job: GitLabJob, dark: Boolean = true) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      JobDetailScreen(
        state = JobDetailState(projectId = 1, jobId = job.id, job = Loadable(job, loaded = true), trace = Loadable(JobTrace("$ ./deploy.sh\\nWaiting for approval\\n", truncated = false), loaded = true)),
        canWrite = true,
        onBack = {},
        onRetryLoad = {},
        onRetryJob = {},
        onOpenInBrowser = {},
        onPlayJob = { played += job.id },
        onCancelJob = { canceled++ },
      )
    }
  }

  @Test
  fun `a manual job can be run from the pipeline`() {
    showPipeline()
    rule.onNodeWithTag("play_42").performClick()
    assertEquals(listOf(42L), played)
    rule.onNodeWithTag("play_41").assertDoesNotExist()
  }

  @Test
  fun `guests can't run manual jobs`() {
    showPipeline(canWrite = false)
    rule.onNodeWithTag("play_42").assertDoesNotExist()
  }

  @Test
  fun `the job page runs a manual job`() {
    showJob(jobs[2])
    rule.onNodeWithTag("run_job").performClick()
    assertEquals(listOf(42L), played)
  }

  @Test
  fun `the job page cancels a running job`() {
    showJob(jobs[1])
    rule.onNodeWithTag("cancel_job").performClick()
    assertEquals(1, canceled)
  }

  @Test fun pipeline_manual_light() { showPipeline(dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/pipeline_manual_job_light.png") }
  @Test fun pipeline_manual_dark() { showPipeline(dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/pipeline_manual_job_dark.png") }
  @Test fun job_manual_light() { showJob(jobs[2], dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/job_manual_light.png") }
  @Test fun job_manual_dark() { showJob(jobs[2], dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/job_manual_dark.png") }
}
