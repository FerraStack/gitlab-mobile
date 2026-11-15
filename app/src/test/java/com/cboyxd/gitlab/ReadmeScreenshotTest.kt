package com.cboyxd.gitlab

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.model.GitLabCommit
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.model.GitLabRelease
import com.cboyxd.gitlab.data.model.GitLabReleaseAssets
import com.cboyxd.gitlab.data.model.GitLabReleaseLink
import com.cboyxd.gitlab.data.model.ProjectCounts
import com.cboyxd.gitlab.ui.components.MarkdownContext
import com.cboyxd.gitlab.ui.screens.FileViewerScreen
import com.cboyxd.gitlab.ui.screens.HomeScreen
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.screens.MergeRequestsScreen
import com.cboyxd.gitlab.ui.screens.PipelineDetailScreen
import com.cboyxd.gitlab.ui.screens.ProjectCodeScreen
import com.cboyxd.gitlab.ui.screens.ProjectDetailScreen
import com.cboyxd.gitlab.ui.screens.ReleaseDetailScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.PipelineDetailState
import com.cboyxd.gitlab.ui.viewmodel.ProjectDetailState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Generates the 10 README screenshots in both light and dark themes using invented data.
 * Record with `./gradlew :app:recordRoborazziDebug --tests "com.cboyxd.gitlab.ReadmeScreenshotTest"`
 * and copy/convert to WebP with `python3 tools/update_readme_screenshots.py`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ReadmeScreenshotTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun capture(
    name: String,
    dark: Boolean,
    afterSetContent: () -> Unit = {},
    content: @Composable () -> Unit,
  ) {
    rule.setContent { GitLabTheme(darkTheme = dark, reducedMotion = true, content = content) }
    rule.waitForIdle()
    afterSetContent()
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/readme_${name}_${if (dark) "dark" else "light"}.png")
  }

  private fun richProjectState(): ProjectDetailState = Fakes.projectState().copy(
    counts = Loadable(ProjectCounts(openIssues = 24, openMergeRequests = 7), loaded = true),
    languages = Loadable(listOf("Kotlin" to 72.4, "Python" to 16.8, "Shell" to 10.8), loaded = true),
    readme = Loadable(
      """
      # Rocket

      An example Android mission-control client built with **Jetpack Compose** and [GitLab](https://example.com).

      ## Quick start

      - [x] Clone the repository
      - [x] Run `./gradlew :app:testDebugUnitTest`
      - [ ] Launch telemetry worker

      | Component | Status | Owner |
      | --- | --- | --- |
      | Core API | Stable | @tanuki |
      | Diff Viewer | Ready | @tanuki |

      ```kotlin
      fun launch(target: String = "orbit"): Boolean = ignite(target)
      ```
      """.trimIndent(),
      loaded = true,
    ),
    releases = Loadable(
      listOf(
        GitLabRelease(
          tagName = "v1.2.0",
          name = "Rocket v1.2.0",
          description = """
            ### Highlights

            - Added word-level syntax highlighting for diffs and commits.
            - Faster pipeline job log streaming with ANSI 256-color support.

            ### Downloads

            Verify the SHA-256 checksums before sideloading.
          """.trimIndent(),
          createdAt = "3d ago",
          releasedAt = "3d ago",
          author = Fakes.user,
          commit = GitLabCommit(id = "a1b2c3d4e5f6", title = "Prepare Rocket v1.2.0 release"),
          assets = GitLabReleaseAssets(
            count = 2,
            links = listOf(
              GitLabReleaseLink(id = 1, name = "rocket-v1.2.0-release.apk", url = "https://example.com/rocket.apk", linkType = "package"),
              GitLabReleaseLink(id = 2, name = "checksums-sha256.txt", url = "https://example.com/sha256.txt", linkType = "other"),
            ),
          ),
        )
      ),
      loaded = true,
    ),
  )

  private fun richPipelineState(): PipelineDetailState = PipelineDetailState(
    projectId = 1,
    pipelineId = 48291,
    pipeline = Loadable(
      GitLabPipeline(
        id = 48291,
        projectId = 1,
        status = "running",
        ref = "fix-launch",
        sha = "9f8e7d6c5b4a",
        duration = 214.0,
        coverage = "91.4",
        source = "merge_request_event",
        user = Fakes.user,
      ),
      loaded = true,
    ),
    jobs = Loadable(
      listOf(
        GitLabJob(id = 101, name = "lint", stage = "build", status = "success", duration = 42.0),
        GitLabJob(id = 102, name = "assembleDebug", stage = "build", status = "success", duration = 76.0),
        GitLabJob(id = 103, name = "unitTests", stage = "test", status = "running", duration = 96.0),
        GitLabJob(id = 104, name = "screenshotTests", stage = "test", status = "success", duration = 64.0),
        GitLabJob(id = 105, name = "publishPlayStore", stage = "deploy", status = "manual"),
      ),
      loaded = true,
    ),
  )

  @Composable
  private fun Home() = HomeScreen(
    state = Fakes.guestState(),
    onRefresh = {},
    onOpenProfile = {},
    onOpenSearch = {},
    onAddServer = {},
    onSignInAgain = {},
    onNavigate = {},
    onSwitchTab = {},
    onToggleStar = {},
  )

  @Composable
  private fun Project() = ProjectDetailScreen(
    state = richProjectState(),
    pendingStar = false,
    canWrite = true,
    markdown = MarkdownContext(),
    onBack = {},
    onRetry = {},
    onToggleStar = {},
    onOpenSection = {},
    onOpenCode = {},
    onOpenRelease = {},
    onOpenInBrowser = {},
  )

  @Composable
  private fun Code() = ProjectCodeScreen(
    state = Fakes.codeState(),
    onBack = {},
    onOpenPath = {},
    onOpenFile = {},
    onChangeRef = {},
    onLoadRefs = {},
    onFilterRefs = {},
    onOpenCommits = {},
    onOpenInBrowser = {},
  )

  @Composable
  private fun File() = FileViewerScreen(
    state = Fakes.file(),
    webUrl = "https://gitlab.com/example-org/rocket",
    markdown = MarkdownContext(),
    onRetry = {},
    onOpenInBrowser = {},
    onBack = {},
  )

  @Composable
  private fun MergeRequests() = MergeRequestsScreen(
    mergeRequests = Loadable(Fakes.mergeRequests, loaded = true),
    projects = Fakes.projects,
    onSelectMergeRequest = {},
    onLoadBranches = { _, _ -> Result.success(emptyList()) },
    onCreateMergeRequest = { _, _, _, _, _, _ -> },
    onRetry = {},
    onBack = {},
  )

  @Composable
  private fun MergeRequest() = MergeRequestDetailScreen(
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
  )

  @Composable
  private fun Pipeline() = PipelineDetailScreen(
    state = richPipelineState(),
    canWrite = true,
    onBack = {},
    onRetryLoad = {},
    onRetryPipeline = {},
    onCancel = {},
    onOpenJob = {},
    onOpenCommit = {},
    onOpenInBrowser = {},
    onPlayJob = {},
  )

  @Composable
  private fun Release() = ReleaseDetailScreen(
    state = richProjectState(),
    tagName = "v1.2.0",
    markdown = MarkdownContext(),
    onBack = {},
    onRetry = {},
    onOpenCommit = {},
    onOpenInBrowser = {},
  )

  private fun scrollToReadme() {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("README.md"))
  }

  private fun openChangesTab() {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("files changed", substring = true))
    rule.onNodeWithText("files changed", substring = true).performClick()
  }

  @Test fun readme_home_light() = capture("home", dark = false) { Home() }
  @Test fun readme_home_dark() = capture("home", dark = true) { Home() }
  @Test fun readme_project_light() = capture("project", dark = false) { Project() }
  @Test fun readme_project_dark() = capture("project", dark = true) { Project() }
  @Test fun readme_readme_light() = capture("readme", dark = false, afterSetContent = ::scrollToReadme) { Project() }
  @Test fun readme_readme_dark() = capture("readme", dark = true, afterSetContent = ::scrollToReadme) { Project() }
  @Test fun readme_code_light() = capture("code", dark = false) { Code() }
  @Test fun readme_code_dark() = capture("code", dark = true) { Code() }
  @Test fun readme_file_light() = capture("file", dark = false) { File() }
  @Test fun readme_file_dark() = capture("file", dark = true) { File() }
  @Test fun readme_merge_requests_light() = capture("merge_requests", dark = false) { MergeRequests() }
  @Test fun readme_merge_requests_dark() = capture("merge_requests", dark = true) { MergeRequests() }
  @Test fun readme_merge_request_light() = capture("merge_request", dark = false) { MergeRequest() }
  @Test fun readme_merge_request_dark() = capture("merge_request", dark = true) { MergeRequest() }
  @Test fun readme_changes_light() = capture("changes", dark = false, afterSetContent = ::openChangesTab) { MergeRequest() }
  @Test fun readme_changes_dark() = capture("changes", dark = true, afterSetContent = ::openChangesTab) { MergeRequest() }
  @Test fun readme_pipeline_light() = capture("pipeline", dark = false) { Pipeline() }
  @Test fun readme_pipeline_dark() = capture("pipeline", dark = true) { Pipeline() }
  @Test fun readme_release_light() = capture("release", dark = false) { Release() }
  @Test fun readme_release_dark() = capture("release", dark = true) { Release() }
}
