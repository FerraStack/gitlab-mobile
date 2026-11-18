package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.model.GitLabGroup
import com.cboyxd.gitlab.ui.viewmodel.BottomTab
import com.cboyxd.gitlab.ui.viewmodel.NavigationStateCodec
import com.cboyxd.gitlab.ui.viewmodel.PersistedNavState
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationStateCodecTest {

  @Test
  fun `round-trips tab and every destination type`() {
    val allDestinations = listOf(
      ScreenDestination.MainTabs,
      ScreenDestination.UserProfile,
      ScreenDestination.Instances,
      ScreenDestination.NotificationSettings,
      ScreenDestination.NotificationDiagnostics,
      ScreenDestination.SecuritySettings,
      ScreenDestination.IssuesList,
      ScreenDestination.MergeRequestsList,
      ScreenDestination.PipelinesList,
      ScreenDestination.GroupsList,
      ScreenDestination.SnippetsList(projectId = null),
      ScreenDestination.SnippetsList(projectId = 42L),
      ScreenDestination.SnippetDetail(snippetId = 99L, projectId = null),
      ScreenDestination.SnippetDetail(snippetId = 100L, projectId = 42L),
      ScreenDestination.GroupProjects(
        GitLabGroup(
          id = 7L,
          name = "Rocket",
          fullPath = "org/rocket",
          description = "Core group",
          avatarUrl = "https://gitlab.com/avatar.png",
          webUrl = "https://gitlab.com/org/rocket",
        )
      ),
      ScreenDestination.ProjectDetail(12L),
      ScreenDestination.ProjectCode(12L),
      ScreenDestination.ProjectSectionList(12L, ProjectSection.ENVIRONMENTS),
      ScreenDestination.ProjectSectionList(12L, ProjectSection.CI_VARIABLES),
      ScreenDestination.FileViewer(projectId = 12L, filePath = "src/Main.kt", ref = "main"),
      ScreenDestination.FileViewer(projectId = 12L, filePath = "README.md", ref = null),
      ScreenDestination.CommitDetail(projectId = 12L, sha = "deadbeef"),
      ScreenDestination.RunPipeline(projectId = 12L),
      ScreenDestination.PipelineDetail(projectId = 12L, pipelineId = 777L),
      ScreenDestination.JobDetail(projectId = 12L, jobId = 888L),
      ScreenDestination.ReleaseDetail(projectId = 12L, tagName = "v1.4.0"),
      ScreenDestination.UserDetail(userId = 55L, username = "tanuki"),
      ScreenDestination.Search(),
      ScreenDestination.Search(projectId = 42L, projectName = "Rocket"),
      ScreenDestination.IssueDetail(projectId = 12L, iid = 3L),
      ScreenDestination.MergeRequestDetail(projectId = 12L, iid = 9L),
    )

    val original = PersistedNavState(
      instanceId = "acc-1",
      tab = BottomTab.INBOX,
      backStack = allDestinations,
    )

    val decoded = NavigationStateCodec.decode(NavigationStateCodec.encode(original))
    assertNotNull(decoded)
    assertEquals(original.instanceId, decoded!!.instanceId)
    assertEquals(original.tab, decoded.tab)
    assertEquals(original.backStack, decoded.backStack)
  }

  @Test
  fun `handles malformed json and unknown values gracefully`() {
    assertNull(NavigationStateCodec.decode(null))
    assertNull(NavigationStateCodec.decode(""))
    assertNull(NavigationStateCodec.decode("not json"))

    val raw = """
      {
        "instanceId": "acc-2",
        "tab": "UNKNOWN_TAB",
        "backStack": [
          {"type": "NonExistentScreen"},
          {"type": "ProjectSectionList", "projectId": 5, "section": "BOGUS"},
          {"type": "IssueDetail", "projectId": 5, "iid": 11}
        ]
      }
    """.trimIndent()

    val decoded = NavigationStateCodec.decode(raw)
    assertNotNull(decoded)
    assertEquals("acc-2", decoded!!.instanceId)
    assertEquals(BottomTab.HOME, decoded.tab)
    assertEquals(listOf(ScreenDestination.IssueDetail(5L, 11L)), decoded.backStack)
  }
}
