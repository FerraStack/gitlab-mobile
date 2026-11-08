package com.cboyxd.gitlab

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cboyxd.gitlab.data.model.GitLabArtifactsFile
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.repository.JobTrace
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.JobDetailState
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The job page offers the job's artifacts archive for download. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class JobArtifactsTest {

  @get:Rule val rule = createComposeRule()
  private val requested = mutableListOf<String>()

  private val build = GitLabJob(
    id = 42,
    name = "assemble-release",
    stage = "build",
    status = "success",
    duration = 312.0,
    ref = "main",
    artifactsFile = GitLabArtifactsFile(filename = "artifacts.zip", size = 18_874_368),
  )

  private fun showJob(job: GitLabJob, dark: Boolean = true, canWrite: Boolean = true) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      JobDetailScreen(
        state = JobDetailState(
          projectId = 1,
          jobId = job.id,
          job = Loadable(job, loaded = true),
          trace = Loadable(JobTrace("\$ ./gradlew assembleRelease\nBUILD SUCCESSFUL in 5m 9s\nUploading artifacts...\napp/build/outputs/apk: found 2 matching files\nJob succeeded\n", truncated = false), loaded = true),
        ),
        canWrite = canWrite,
        onBack = {},
        onRetryLoad = {},
        onRetryJob = {},
        onOpenInBrowser = {},
        onDownloadArtifacts = { requested += it },
      )
    }
  }

  @Test
  fun `the button shows the archive size and suggests a name`() {
    showJob(build)
    rule.onNodeWithTag("download_artifacts").assertTextContains("Download artifacts · 18 MB").performClick()
    assertEquals(listOf("assemble-release-42-artifacts.zip"), requested)
  }

  @Test
  fun `guests can download public artifacts`() {
    showJob(build, canWrite = false)
    rule.onNodeWithTag("download_artifacts").performClick()
    assertEquals(1, requested.size)
  }

  @Test
  fun `a job without artifacts has no button`() {
    showJob(build.copy(artifactsFile = null))
    rule.onNodeWithTag("download_artifacts").assertDoesNotExist()
  }

  @Test fun job_artifacts_light() { showJob(build, dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/job_artifacts_light.png") }
  @Test fun job_artifacts_dark() { showJob(build, dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/job_artifacts_dark.png") }
}
