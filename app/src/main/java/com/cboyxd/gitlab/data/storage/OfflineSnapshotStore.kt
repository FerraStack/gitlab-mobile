package com.cboyxd.gitlab.data.storage

import android.content.Context
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.model.GitLabEvent
import com.cboyxd.gitlab.data.model.GitLabGroup
import com.cboyxd.gitlab.data.model.GitLabIssue
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.model.GitLabProject
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.data.model.GitLabUserStatus
import com.cboyxd.gitlab.ui.viewmodel.GitLabNotification
import com.squareup.moshi.JsonClass
import java.io.File

@JsonClass(generateAdapter = true)
data class DashboardSnapshot(
  val savedAtMs: Long = 0L,
  val currentUser: GitLabUser? = null,
  val userStatus: GitLabUserStatus? = null,
  val profileReadme: String? = null,
  val projects: List<GitLabProject> = emptyList(),
  val groups: List<GitLabGroup> = emptyList(),
  val issues: List<GitLabIssue> = emptyList(),
  val mergeRequests: List<GitLabMergeRequest> = emptyList(),
  val pipelines: List<GitLabPipeline> = emptyList(),
  val notifications: List<GitLabNotification> = emptyList(),
  val activity: List<GitLabEvent> = emptyList(),
  val recentIssues: List<GitLabIssue> = emptyList(),
  val recentMergeRequests: List<GitLabMergeRequest> = emptyList(),
)

/**
 * Persists a lightweight JSON snapshot of the last-seen Home / Projects / Inbox data and recently
 * opened issues/MRs per account so opening the app offline shows meaningful content immediately.
 */
class OfflineSnapshotStore(private val rootDir: File) {

  constructor(context: Context) : this(File(context.filesDir, "offline_snapshots"))

  private val adapter by lazy { GitLabApiClient.moshi.adapter(DashboardSnapshot::class.java) }

  private fun fileFor(instanceId: String): File {
    val safeId = instanceId.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(rootDir, "$safeId.json")
  }

  fun read(instanceId: String): DashboardSnapshot? = synchronized(this) {
    val file = fileFor(instanceId)
    if (!file.isFile) return null
    return try {
      adapter.fromJson(file.readText())
    } catch (_: Exception) {
      null
    }
  }

  fun write(instanceId: String, snapshot: DashboardSnapshot) = synchronized(this) {
    writeLocked(instanceId, snapshot)
  }

  fun recordRecentIssue(instanceId: String, issue: GitLabIssue, maxItems: Int = 15) = synchronized(this) {
    val current = read(instanceId) ?: DashboardSnapshot()
    val updated = (listOf(issue) + current.recentIssues.filterNot { it.projectId == issue.projectId && it.iid == issue.iid })
      .take(maxItems)
    writeLocked(instanceId, current.copy(recentIssues = updated))
  }

  fun recordRecentMergeRequest(instanceId: String, mr: GitLabMergeRequest, maxItems: Int = 15) = synchronized(this) {
    val current = read(instanceId) ?: DashboardSnapshot()
    val updated = (listOf(mr) + current.recentMergeRequests.filterNot { it.projectId == mr.projectId && it.iid == mr.iid })
      .take(maxItems)
    writeLocked(instanceId, current.copy(recentMergeRequests = updated))
  }

  private fun writeLocked(instanceId: String, snapshot: DashboardSnapshot) {
    try {
      if (!rootDir.exists()) rootDir.mkdirs()
      val file = fileFor(instanceId)
      val tmp = File(rootDir, "${file.name}.tmp")
      tmp.writeText(adapter.toJson(snapshot))
      if (!tmp.renameTo(file)) {
        file.writeText(tmp.readText())
        tmp.delete()
      }
    } catch (_: Exception) {
    }
  }

  fun delete(instanceId: String) = synchronized(this) {
    try {
      fileFor(instanceId).delete()
    } catch (_: Exception) {
    }
  }

  fun clearAll() = synchronized(this) {
    try {
      rootDir.listFiles()?.forEach { it.delete() }
    } catch (_: Exception) {
    }
  }
}
