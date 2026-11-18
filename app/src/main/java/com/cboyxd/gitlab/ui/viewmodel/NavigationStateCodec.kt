package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.model.GitLabGroup
import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedNavState(
  val instanceId: String,
  val tab: BottomTab,
  val backStack: List<ScreenDestination>,
)

/**
 * Serializes navigation tab + back stack into compact JSON for SavedStateHandle so Android
 * process death restores the user's current screen instead of dropping back to Home.
 */
internal object NavigationStateCodec {

  fun encode(state: PersistedNavState): String {
    val root = JSONObject()
    root.put("instanceId", state.instanceId)
    root.put("tab", state.tab.name)
    val stack = JSONArray()
    state.backStack.forEach { dest -> encodeDestination(dest)?.let(stack::put) }
    root.put("backStack", stack)
    return root.toString()
  }

  fun decode(raw: String?): PersistedNavState? {
    if (raw.isNullOrBlank()) return null
    return try {
      val root = JSONObject(raw)
      val instanceId = root.optString("instanceId", "")
      val tab = BottomTab.entries.firstOrNull { it.name == root.optString("tab") } ?: BottomTab.HOME
      val array = root.optJSONArray("backStack")
      val backStack = buildList {
        if (array != null) {
          for (i in 0 until array.length()) {
            decodeDestination(array.optJSONObject(i))?.let(::add)
          }
        }
      }
      PersistedNavState(instanceId = instanceId, tab = tab, backStack = backStack)
    } catch (_: Exception) {
      null
    }
  }

  private fun encodeDestination(dest: ScreenDestination): JSONObject? {
    val obj = JSONObject()
    when (dest) {
      ScreenDestination.MainTabs -> obj.put("type", "MainTabs")
      ScreenDestination.UserProfile -> obj.put("type", "UserProfile")
      ScreenDestination.Instances -> obj.put("type", "Instances")
      ScreenDestination.NotificationSettings -> obj.put("type", "NotificationSettings")
      ScreenDestination.NotificationDiagnostics -> obj.put("type", "NotificationDiagnostics")
      ScreenDestination.SecuritySettings -> obj.put("type", "SecuritySettings")
      ScreenDestination.IssuesList -> obj.put("type", "IssuesList")
      ScreenDestination.MergeRequestsList -> obj.put("type", "MergeRequestsList")
      ScreenDestination.PipelinesList -> obj.put("type", "PipelinesList")
      ScreenDestination.GroupsList -> obj.put("type", "GroupsList")
      is ScreenDestination.SnippetsList -> {
        obj.put("type", "SnippetsList")
        dest.projectId?.let { obj.put("projectId", it) }
      }
      is ScreenDestination.SnippetDetail -> {
        obj.put("type", "SnippetDetail")
        obj.put("snippetId", dest.snippetId)
        dest.projectId?.let { obj.put("projectId", it) }
      }
      is ScreenDestination.GroupProjects -> {
        obj.put("type", "GroupProjects")
        obj.put("groupId", dest.group.id)
        obj.put("groupName", dest.group.name)
        obj.put("groupFullPath", dest.group.fullPath)
        dest.group.description?.let { obj.put("groupDescription", it) }
        dest.group.avatarUrl?.let { obj.put("groupAvatarUrl", it) }
        obj.put("groupWebUrl", dest.group.webUrl)
      }
      is ScreenDestination.ProjectDetail -> {
        obj.put("type", "ProjectDetail")
        obj.put("projectId", dest.projectId)
      }
      is ScreenDestination.ProjectCode -> {
        obj.put("type", "ProjectCode")
        obj.put("projectId", dest.projectId)
      }
      is ScreenDestination.ProjectSectionList -> {
        obj.put("type", "ProjectSectionList")
        obj.put("projectId", dest.projectId)
        obj.put("section", dest.section.name)
      }
      is ScreenDestination.FileViewer -> {
        obj.put("type", "FileViewer")
        obj.put("projectId", dest.projectId)
        obj.put("filePath", dest.filePath)
        dest.ref?.let { obj.put("ref", it) }
      }
      is ScreenDestination.CommitDetail -> {
        obj.put("type", "CommitDetail")
        obj.put("projectId", dest.projectId)
        obj.put("sha", dest.sha)
      }
      is ScreenDestination.RunPipeline -> {
        obj.put("type", "RunPipeline")
        obj.put("projectId", dest.projectId)
      }
      is ScreenDestination.PipelineDetail -> {
        obj.put("type", "PipelineDetail")
        obj.put("projectId", dest.projectId)
        obj.put("pipelineId", dest.pipelineId)
      }
      is ScreenDestination.JobDetail -> {
        obj.put("type", "JobDetail")
        obj.put("projectId", dest.projectId)
        obj.put("jobId", dest.jobId)
      }
      is ScreenDestination.ReleaseDetail -> {
        obj.put("type", "ReleaseDetail")
        obj.put("projectId", dest.projectId)
        obj.put("tagName", dest.tagName)
      }
      is ScreenDestination.UserDetail -> {
        obj.put("type", "UserDetail")
        obj.put("userId", dest.userId)
        obj.put("username", dest.username)
      }
      is ScreenDestination.Search -> {
        obj.put("type", "Search")
        dest.projectId?.let { obj.put("projectId", it) }
        dest.projectName?.let { obj.put("projectName", it) }
      }
      is ScreenDestination.IssueDetail -> {
        obj.put("type", "IssueDetail")
        obj.put("projectId", dest.projectId)
        obj.put("iid", dest.iid)
      }
      is ScreenDestination.MergeRequestDetail -> {
        obj.put("type", "MergeRequestDetail")
        obj.put("projectId", dest.projectId)
        obj.put("iid", dest.iid)
      }
    }
    return obj
  }

  private fun decodeDestination(obj: JSONObject?): ScreenDestination? {
    if (obj == null) return null
    return when (obj.optString("type")) {
      "MainTabs" -> ScreenDestination.MainTabs
      "UserProfile" -> ScreenDestination.UserProfile
      "Instances" -> ScreenDestination.Instances
      "NotificationSettings" -> ScreenDestination.NotificationSettings
      "NotificationDiagnostics" -> ScreenDestination.NotificationDiagnostics
      "SecuritySettings" -> ScreenDestination.SecuritySettings
      "IssuesList" -> ScreenDestination.IssuesList
      "MergeRequestsList" -> ScreenDestination.MergeRequestsList
      "PipelinesList" -> ScreenDestination.PipelinesList
      "GroupsList" -> ScreenDestination.GroupsList
      "SnippetsList" -> ScreenDestination.SnippetsList(
        projectId = if (obj.has("projectId")) obj.optLong("projectId") else null
      )
      "SnippetDetail" -> ScreenDestination.SnippetDetail(
        snippetId = obj.optLong("snippetId"),
        projectId = if (obj.has("projectId")) obj.optLong("projectId") else null
      )
      "GroupProjects" -> ScreenDestination.GroupProjects(
        GitLabGroup(
          id = obj.optLong("groupId"),
          name = obj.optString("groupName", ""),
          fullPath = obj.optString("groupFullPath", ""),
          description = if (obj.has("groupDescription")) obj.optString("groupDescription") else null,
          avatarUrl = if (obj.has("groupAvatarUrl")) obj.optString("groupAvatarUrl") else null,
          webUrl = obj.optString("groupWebUrl", ""),
        )
      )
      "ProjectDetail" -> ScreenDestination.ProjectDetail(obj.optLong("projectId"))
      "ProjectCode" -> ScreenDestination.ProjectCode(obj.optLong("projectId"))
      "ProjectSectionList" -> {
        val section = ProjectSection.entries.firstOrNull { it.name == obj.optString("section") } ?: return null
        ScreenDestination.ProjectSectionList(obj.optLong("projectId"), section)
      }
      "FileViewer" -> ScreenDestination.FileViewer(
        projectId = obj.optLong("projectId"),
        filePath = obj.optString("filePath", ""),
        ref = if (obj.has("ref")) obj.optString("ref") else null,
      )
      "CommitDetail" -> ScreenDestination.CommitDetail(
        projectId = obj.optLong("projectId"),
        sha = obj.optString("sha", ""),
      )
      "RunPipeline" -> ScreenDestination.RunPipeline(obj.optLong("projectId"))
      "PipelineDetail" -> ScreenDestination.PipelineDetail(
        projectId = obj.optLong("projectId"),
        pipelineId = obj.optLong("pipelineId"),
      )
      "JobDetail" -> ScreenDestination.JobDetail(
        projectId = obj.optLong("projectId"),
        jobId = obj.optLong("jobId"),
      )
      "ReleaseDetail" -> ScreenDestination.ReleaseDetail(
        projectId = obj.optLong("projectId"),
        tagName = obj.optString("tagName", ""),
      )
      "UserDetail" -> ScreenDestination.UserDetail(
        userId = obj.optLong("userId"),
        username = obj.optString("username", ""),
      )
      "Search" -> ScreenDestination.Search(
        projectId = if (obj.has("projectId")) obj.optLong("projectId") else null,
        projectName = if (obj.has("projectName")) obj.optString("projectName") else null,
      )
      "IssueDetail" -> ScreenDestination.IssueDetail(
        projectId = obj.optLong("projectId"),
        iid = obj.optLong("iid"),
      )
      "MergeRequestDetail" -> ScreenDestination.MergeRequestDetail(
        projectId = obj.optLong("projectId"),
        iid = obj.optLong("iid"),
      )
      else -> null
    }
  }
}
