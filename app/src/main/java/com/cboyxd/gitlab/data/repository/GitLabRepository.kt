package com.cboyxd.gitlab.data.repository

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.api.GitLabApiService
import com.cboyxd.gitlab.data.api.encodeGitLabPath
import com.cboyxd.gitlab.data.api.formatGitLabTime
import com.cboyxd.gitlab.data.api.parseGitLabMillis
import com.cboyxd.gitlab.data.api.requireBody
import com.cboyxd.gitlab.data.api.requireList
import com.cboyxd.gitlab.data.api.stagesFromJobs
import com.cboyxd.gitlab.data.api.toGitLabException
import com.cboyxd.gitlab.data.model.GitLabApprovals
import com.cboyxd.gitlab.data.model.GitLabAwardEmoji
import com.cboyxd.gitlab.data.model.GitLabBlobSearchResult
import com.cboyxd.gitlab.data.model.GitLabBranch
import com.cboyxd.gitlab.data.model.GitLabCiVariable
import com.cboyxd.gitlab.data.model.GitLabCommit
import com.cboyxd.gitlab.data.model.GitLabContributor
import com.cboyxd.gitlab.data.model.GitLabDeployment
import com.cboyxd.gitlab.data.model.GitLabDiscussion
import com.cboyxd.gitlab.data.model.GitLabDiffRefs
import com.cboyxd.gitlab.data.model.DiffLineAnchor
import com.cboyxd.gitlab.data.model.GitLabEnvironment
import com.cboyxd.gitlab.data.model.diffNotePosition
import com.cboyxd.gitlab.data.model.GitLabEvent
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.model.GitLabLabel
import com.cboyxd.gitlab.data.model.GitLabMilestone
import com.cboyxd.gitlab.data.model.GitLabNamespace
import com.cboyxd.gitlab.data.model.GitLabNoteSearchResult
import com.cboyxd.gitlab.data.model.GitLabRelease
import com.cboyxd.gitlab.data.model.GitLabSnippet
import com.cboyxd.gitlab.data.model.GitLabTag
import com.cboyxd.gitlab.data.model.GitLabFileDiff
import com.cboyxd.gitlab.data.model.GitLabGroup
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabIssue
import com.cboyxd.gitlab.data.model.GitLabIssueUpdate
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabMergeRequestUpdate
import com.cboyxd.gitlab.data.model.MergeOptions
import com.cboyxd.gitlab.data.model.GitLabNote
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.model.GitLabProject
import com.cboyxd.gitlab.data.model.ProjectCounts
import com.cboyxd.gitlab.data.model.GitLabTodo
import com.cboyxd.gitlab.data.model.GitLabTreeItem
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.data.model.GitLabUserStatus
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class JobTrace(
  val text: String,
  val truncated: Boolean,
  val startOffset: Long = 0L,
  val earlierLines: Int? = null,
)

/** One page of a list and the page after it, or null when this was the last. */
data class ListPage<T>(val items: List<T>, val nextPage: Int?, val totalCount: Int? = null)

/** Bounded multi-page fetch result retaining the server's total count and whether more pages exist. */
data class PagedSlice<T>(
  val items: List<T>,
  val totalCount: Int? = null,
  val truncated: Boolean = false,
)

/** Items fetched from GitLab along with the server's Date header in epoch millis, when present. */
data class ServerFeed<T>(val items: List<T>, val serverTimeMs: Long?)

data class ConnectionProbe(
  val version: String,
  val username: String,
  val name: String
)

/**
 * Stateless access to one GitLab server per call. Every list is bounded by [maxPages]: the app
 * shows recent activity, it never walks a whole history (all commits, all pipelines, all done
 * to-dos), which is what used to make screens hang on large servers.
 */
class GitLabRepository {
  private data class CachedAvatar(val url: String?, val expiresAt: Long)
  private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, CachedAvatar>()
  private val avatarSlots = Semaphore(4)

  private suspend fun contributorAvatar(instance: GitLabInstance, email: String): String? {
    if (email.isBlank()) return null
    val key = "${instance.id}:${instance.url}:$email"
    avatarCache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.url }
    return avatarSlots.withPermit {
      avatarCache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withPermit it.url }
      val url = try {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { service(instance).getAvatar(email).body()?.avatarUrl }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (_: Exception) {
        null
      }
      if (avatarCache.size >= 512) avatarCache.clear()
      avatarCache[key] = CachedAvatar(url, System.currentTimeMillis() + if (url == null) 60_000 else 30 * 60_000)
      url
    }
  }


  suspend fun testConnection(instance: GitLabInstance): Result<ConnectionProbe> = call {
    val service = service(instance)
    if (instance.token.isNotBlank()) {
      val user = service.getCurrentUser().requireBody()
      ConnectionProbe(version = versionOrBlank(service), username = user.username, name = user.name)
    } else {
      service.getProjects(visibility = "public", perPage = 1).requireList()
      ConnectionProbe(version = versionOrBlank(service), username = "", name = "")
    }
  }

  suspend fun getVersion(instance: GitLabInstance): Result<String> = call {
    service(instance).getVersion().requireBody().version
  }

  suspend fun getCurrentUser(instance: GitLabInstance): Result<GitLabUser?> = call {
    if (instance.token.isBlank()) null else service(instance).getCurrentUser().requireBody()
  }

  suspend fun getUserStatus(instance: GitLabInstance): Result<GitLabUserStatus> = call {
    requireToken(instance)
    service(instance).getUserStatus().requireBody()
  }

  suspend fun setUserStatus(instance: GitLabInstance, message: String): Result<GitLabUserStatus> = call {
    requireToken(instance)
    val emoji = if (message.isBlank()) null else "speech_balloon"
    service(instance).setUserStatus(message = message, emoji = emoji).requireBody()
  }

  suspend fun getProfileReadme(instance: GitLabInstance, username: String): Result<String?> = call {
    if (username.isBlank()) return@call null
    val service = service(instance)
    val response = service.getProject(encodeGitLabPath("$username/$username"))
    if (response.code() == 404 || response.code() == 403) return@call null
    val project = response.requireBody()
    readReadme(service, project.id, project.defaultBranch)
  }

  suspend fun getProjects(instance: GitLabInstance, search: String? = null): Result<List<GitLabProject>> = call {
    val service = service(instance)
    val query = search?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    val signedIn = instance.token.isNotBlank()
    if (query != null) {
      // A pasted path ("group/project") opens that project directly, even outside the search index.
      val byPath = if ('/' in query) {
        runCatchingApi { service.getProject(encodeGitLabPath(query)).takeIf { it.isSuccessful }?.body() }.getOrNull()
      } else null
      val found = fetchPages(maxPages = 1) { page ->
        service.getProjects(
          membership = if (signedIn) true else null,
          visibility = if (signedIn) null else "public",
          search = query,
          searchNamespaces = if ('/' in query) true else null,
          perPage = 50,
          page = page
        )
      }
      return@call (listOfNotNull(byPath) + found).distinctBy { it.id }.map { it.withDisplayTimes() }
    }
    if (!signedIn) {
      // Without an account there is no membership; show the most starred public projects instead.
      return@call fetchPages(maxPages = 1) { page ->
        service.getProjects(visibility = "public", orderBy = "star_count", perPage = 20, page = page)
      }.map { it.withDisplayTimes() }
    }
    coroutineScope {
      val member = async { fetchPages(maxPages = 2) { page -> service.getProjects(membership = true, perPage = 100, page = page) } }
      val starred = async {
        try {
          fetchPages(maxPages = 1) { page -> service.getProjects(starred = true, perPage = 100, page = page) }
        } catch (_: GitLabApiException) {
          emptyList()
        }
      }
      mergeProjects(member.await(), starred.await()).map { it.withDisplayTimes() }
    }
  }

  suspend fun getProject(instance: GitLabInstance, projectId: Long): Result<GitLabProject> = call {
    service(instance).getProject(projectId.toString()).requireBody().withDisplayTimes()
  }

  suspend fun createProject(
    instance: GitLabInstance,
    name: String,
    description: String?,
    visibility: String
  ): Result<GitLabProject> = call {
    requireToken(instance)
    service(instance).createProject(name, description?.takeIf { it.isNotBlank() }, visibility)
      .requireBody()
      .withDisplayTimes()
  }

  suspend fun setProjectStarred(
    instance: GitLabInstance,
    projectId: Long,
    starred: Boolean
  ): Result<GitLabProject> = call {
    requireToken(instance)
    val service = service(instance)
    val response = if (starred) service.starProject(projectId) else service.unstarProject(projectId)
    // 304 means it already had that state; reload so the counts stay truthful.
    val project = if (response.code() == 304) service.getProject(projectId.toString()).requireBody() else response.requireBody()
    project.copy(isStarred = starred).withDisplayTimes()
  }

  suspend fun getGroups(instance: GitLabInstance): Result<List<GitLabGroup>> = call {
    if (instance.token.isBlank()) return@call emptyList()
    fetchPages(maxPages = 2) { page -> service(instance).getGroups(membership = true, perPage = 100, page = page) }
  }

  suspend fun getGroupProjects(instance: GitLabInstance, groupPath: String): Result<List<GitLabProject>> = call {
    fetchPages(maxPages = 2) { page -> service(instance).getGroupProjects(encodeGitLabPath(groupPath), perPage = 100, page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun getMyIssues(instance: GitLabInstance): Result<List<GitLabIssue>> =
    getMyIssuesPage(instance).map { it.items }

  suspend fun getMyIssuesPage(instance: GitLabInstance): Result<PagedSlice<GitLabIssue>> = call {
    if (instance.token.isBlank()) return@call PagedSlice(emptyList())
    val service = service(instance)
    coroutineScope {
      val assigned = async { fetchSlice(maxPages = 1) { page -> service.getIssues(scope = "assigned_to_me", perPage = 100, page = page) } }
      val created = async {
        runCatchingApi { fetchSlice(maxPages = 1) { page -> service.getIssues(scope = "created_by_me", perPage = 100, page = page) } }
      }
      val assignedSlice = assigned.await()
      val createdSlice = created.await().getOrElse { error -> if (assignedSlice.items.isEmpty()) throw error else PagedSlice(emptyList()) }
      val merged = (assignedSlice.items + createdSlice.items)
        .distinctBy { it.projectId to it.iid }
        .newestFirst { it.updatedAt }
        .map { it.withDisplayTimes() }
      PagedSlice(
        items = merged,
        truncated = assignedSlice.truncated || createdSlice.truncated,
      )
    }
  }

  suspend fun getIssue(instance: GitLabInstance, projectId: Long, iid: Long): Result<GitLabIssue> = call {
    service(instance).getIssue(projectId, iid).requireBody().withDisplayTimes()
  }

  /** One page of a project's issues in [state] ("opened", "closed" or "all"), newest activity first. */
  suspend fun getProjectIssues(instance: GitLabInstance, projectId: Long, state: String = "opened", page: Int = 1): Result<ListPage<GitLabIssue>> = call {
    val response = service(instance).getProjectIssues(projectId, state = state, perPage = PROJECT_PAGE_SIZE, page = page)
    if (!response.isSuccessful) throw response.toGitLabException()
    ListPage(response.body().orEmpty().map { it.withDisplayTimes() }, nextPage(response, page))
  }

  /** Exact issue counts per state ("opened", "closed", "all") from issues_statistics; missing when unknown. */
  suspend fun getProjectIssueStateCounts(instance: GitLabInstance, projectId: Long): Result<Map<String, Int>> = call {
    val counts = countOrNullValue { service(instance).getIssueStatistics(projectId).takeIf { it.isSuccessful }?.body()?.statistics?.counts }
    buildMap {
      counts?.opened?.let { put("opened", it) }
      counts?.closed?.let { put("closed", it) }
      counts?.all?.let { put("all", it) }
    }
  }

  suspend fun createIssue(
    instance: GitLabInstance,
    projectId: Long,
    title: String,
    description: String?,
    labels: String?
  ): Result<GitLabIssue> = call {
    requireToken(instance)
    if (projectId <= 0) throw GitLabApiException(400, "Choose a project for the issue.")
    service(instance).createIssue(projectId, title, description, labels).requireBody().withDisplayTimes()
  }

  suspend fun setIssueState(instance: GitLabInstance, issue: GitLabIssue, close: Boolean): Result<GitLabIssue> = call {
    requireToken(instance)
    service(instance).setIssueState(issue.projectId, issue.iid, if (close) "close" else "reopen")
      .requireBody().withDisplayTimes()
  }

  suspend fun updateIssue(instance: GitLabInstance, issue: GitLabIssue, title: String, description: String?): Result<GitLabIssue> = call {
    requireToken(instance)
    service(instance).updateIssue(issue.projectId, issue.iid, title, description)
      .requireBody().withDisplayTimes()
  }

  suspend fun triageIssue(
    instance: GitLabInstance,
    projectId: Long,
    issueIid: Long,
    update: GitLabIssueUpdate,
  ): Result<GitLabIssue> = call {
    requireToken(instance)
    service(instance).triageIssue(projectId, issueIid, update).requireBody().withDisplayTimes()
  }

  suspend fun updateMergeRequest(
    instance: GitLabInstance,
    projectId: Long,
    mrIid: Long,
    update: GitLabMergeRequestUpdate,
  ): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    service(instance).updateMergeRequest(projectId, mrIid, update).requireBody().withDisplayTimes()
  }

  suspend fun getIssueNotes(instance: GitLabInstance, projectId: Long, issueIid: Long): Result<List<GitLabNote>> = call {
    fetchPages(maxPages = 5) { page -> service(instance).getIssueNotes(projectId, issueIid, perPage = 100, page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun createIssueNote(
    instance: GitLabInstance,
    projectId: Long,
    issueIid: Long,
    body: String
  ): Result<GitLabNote> = call {
    requireToken(instance)
    service(instance).createIssueNote(projectId, issueIid, body).requireBody().withDisplayTimes()
  }

  suspend fun getMyMergeRequests(instance: GitLabInstance): Result<List<GitLabMergeRequest>> =
    getMyMergeRequestsPage(instance).map { it.items }

  suspend fun getMyMergeRequestsPage(instance: GitLabInstance): Result<PagedSlice<GitLabMergeRequest>> = call {
    if (instance.token.isBlank()) return@call PagedSlice(emptyList())
    val service = service(instance)
    coroutineScope {
      val assigned = async { fetchSlice(maxPages = 1) { page -> service.getMergeRequests(scope = "assigned_to_me", perPage = 100, page = page) } }
      val created = async {
        runCatchingApi { fetchSlice(maxPages = 1) { page -> service.getMergeRequests(scope = "created_by_me", perPage = 100, page = page) } }
      }
      val reviews = async {
        if (instance.username.isBlank()) PagedSlice(emptyList())
        else runCatchingApi {
          fetchSlice(maxPages = 1) { page ->
            service.getMergeRequests(scope = "all", reviewerUsername = instance.username, state = "opened", perPage = 100, page = page)
          }
        }.getOrDefault(PagedSlice(emptyList()))
      }
      val assignedSlice = assigned.await()
      val createdSlice = created.await().getOrElse { error -> if (assignedSlice.items.isEmpty()) throw error else PagedSlice(emptyList()) }
      val reviewsSlice = reviews.await()
      val merged = (assignedSlice.items + createdSlice.items + reviewsSlice.items)
        .distinctBy { it.projectId to it.iid }
        .newestFirst { it.updatedAt }
        .map { it.withDisplayTimes() }
      PagedSlice(
        items = merged,
        truncated = assignedSlice.truncated || createdSlice.truncated || reviewsSlice.truncated,
      )
    }
  }

  /** Raw timestamps are retained for background event detection, along with the server's Date header. */
  suspend fun getNotificationMergeRequestsFeed(
    instance: GitLabInstance,
    scope: String,
    updatedAfter: String? = null,
  ): Result<ServerFeed<GitLabMergeRequest>> = call {
    requireToken(instance)
    fetchPagesWithServerTime(maxPages = 5) { page ->
      service(instance).getMergeRequests(
        scope = if (scope == "reviews_for_me" && instance.username.isNotBlank()) "all" else scope,
        reviewerUsername = instance.username.takeIf { scope == "reviews_for_me" && it.isNotBlank() },
        state = if (scope == "created_by_me") "all" else "opened",
        perPage = 100,
        withLabelDetails = false,
        page = page,
        updatedAfter = updatedAfter,
      )
    }
  }

  /** Raw timestamps are retained for background event detection. */
  suspend fun getNotificationMergeRequests(
    instance: GitLabInstance,
    scope: String,
    updatedAfter: String? = null,
  ): Result<List<GitLabMergeRequest>> = getNotificationMergeRequestsFeed(instance, scope, updatedAfter).map { it.items }

  suspend fun getMergeRequest(instance: GitLabInstance, projectId: Long, iid: Long): Result<GitLabMergeRequest> = call {
    service(instance).getMergeRequest(projectId, iid).requireBody().withDisplayTimes()
  }

  /** One page of a project's merge requests in [state] ("opened", "merged", "closed" or "all"). */
  suspend fun getProjectMergeRequests(instance: GitLabInstance, projectId: Long, state: String = "opened", page: Int = 1): Result<ListPage<GitLabMergeRequest>> = call {
    val response = service(instance).getProjectMergeRequests(projectId, state = state, perPage = PROJECT_PAGE_SIZE, page = page)
    if (!response.isSuccessful) throw response.toGitLabException()
    ListPage(response.body().orEmpty().map { it.withDisplayTimes() }, nextPage(response, page))
  }

  /** Exact merge request counts per state, from the X-Total of one-item pages; missing when unknown. */
  suspend fun getProjectMergeRequestStateCounts(instance: GitLabInstance, projectId: Long): Result<Map<String, Int>> = call {
    val service = service(instance)
    coroutineScope {
      listOf("opened", "merged", "closed", "all").map { state ->
        async {
          state to countOrNull {
            service.countProjectMergeRequests(projectId, state = state).takeIf { it.isSuccessful }?.headers()?.get("X-Total")?.toIntOrNull()
          }
        }
      }.map { it.await() }.mapNotNull { (state, count) -> count?.let { state to it } }.toMap()
    }
  }

  private fun nextPage(response: Response<*>, page: Int): Int? =
    response.headers()["X-Next-Page"]?.toIntOrNull()?.takeIf { it > page }

  /**
   * Exact open issue and merge request counts. The project's own open_issues_count is a cached
   * counter that GitLab refreshes lazily and leaves out for anonymous requests, so the page asks
   * issues_statistics and the X-Total of a one-item merge request page instead.
   */
  suspend fun getProjectCounts(instance: GitLabInstance, projectId: Long): Result<ProjectCounts> = call {
    val service = service(instance)
    coroutineScope {
      val issues = async {
        countOrNull { service.getIssueStatistics(projectId).takeIf { it.isSuccessful }?.body()?.statistics?.counts?.opened }
      }
      val mergeRequests = async {
        countOrNull { service.countProjectMergeRequests(projectId).takeIf { it.isSuccessful }?.headers()?.get("X-Total")?.toIntOrNull() }
      }
      ProjectCounts(openIssues = issues.await(), openMergeRequests = mergeRequests.await())
    }
  }

  suspend fun createMergeRequest(
    instance: GitLabInstance,
    projectId: Long,
    title: String,
    sourceBranch: String,
    targetBranch: String,
    description: String?
  ): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    if (projectId <= 0) throw GitLabApiException(400, "Choose a project for the merge request.")
    if (title.isBlank()) throw GitLabApiException(400, "Enter a title for the merge request.")
    if (sourceBranch.isBlank() || targetBranch.isBlank()) throw GitLabApiException(400, "Choose both branches.")
    if (sourceBranch == targetBranch) throw GitLabApiException(400, "Source and target branches must be different.")
    service(instance).createMergeRequest(projectId, title, sourceBranch, targetBranch, description)
      .requireBody().withDisplayTimes()
  }

  suspend fun setMergeRequestState(instance: GitLabInstance, mr: GitLabMergeRequest, close: Boolean): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    service(instance).setMergeRequestState(mr.projectId, mr.iid, if (close) "close" else "reopen")
      .requireBody().withDisplayTimes()
  }

  suspend fun mergeMergeRequest(instance: GitLabInstance, mr: GitLabMergeRequest, options: MergeOptions = MergeOptions()): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    // Pin the merge to the commit the user saw, so commits pushed after the review aren't merged.
    val response = service(instance).mergeMergeRequest(
      mr.projectId,
      mr.iid,
      removeSourceBranch = options.deleteSourceBranch,
      sha = mr.sha?.takeIf { it.isNotBlank() },
      squash = options.squash,
      whenPipelineSucceeds = options.whenPipelineSucceeds.takeIf { it },
    )
    when (response.code()) {
      405 -> throw GitLabApiException(405, "This merge request can't be merged yet. Resolve its blocking items in GitLab, then try again.")
      406 -> throw GitLabApiException(406, "This merge request has conflicts. Resolve them, then merge.")
      409 -> throw GitLabApiException(409, "The source branch changed since you opened this merge request. Refresh it, review the new commits, then merge.")
    }
    response.requireBody().withDisplayTimes()
  }

  /** Marks a draft as ready by dropping its draft prefix, as GitLab's "Mark as ready" does. */
  suspend fun markMergeRequestReady(instance: GitLabInstance, mr: GitLabMergeRequest): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    val title = withoutDraftPrefix(mr.title)
    if (title.isBlank()) throw GitLabApiException(400, "The title can't be empty without its draft prefix.")
    service(instance).updateMergeRequestTitle(mr.projectId, mr.iid, title).requireBody().withDisplayTimes()
  }

  suspend fun rebaseMergeRequest(instance: GitLabInstance, mr: GitLabMergeRequest): Result<Unit> = call {
    requireToken(instance)
    val response = service(instance).rebaseMergeRequest(mr.projectId, mr.iid)
    when (response.code()) {
      403 -> throw GitLabApiException(403, "You can't rebase this merge request.")
      409 -> throw GitLabApiException(409, "A rebase is already running.")
    }
    if (!response.isSuccessful) throw response.toGitLabException()
    response.body()?.close()
  }

  suspend fun cancelAutoMerge(instance: GitLabInstance, mr: GitLabMergeRequest): Result<GitLabMergeRequest> = call {
    requireToken(instance)
    service(instance).cancelAutoMerge(mr.projectId, mr.iid).requireBody().withDisplayTimes()
  }

  suspend fun getMergeRequestDiffs(
    instance: GitLabInstance,
    projectId: Long,
    mrIid: Long
  ): Result<List<GitLabFileDiff>> = getMergeRequestDiffsPage(instance, projectId, mrIid).map { it.items }

  suspend fun getMergeRequestDiffsPage(
    instance: GitLabInstance,
    projectId: Long,
    mrIid: Long
  ): Result<PagedSlice<GitLabFileDiff>> = call {
    val service = service(instance)
    try {
      fetchSlice(maxPages = MAX_DIFF_PAGES) { page ->
        service.getMergeRequestDiffs(projectId, mrIid, perPage = 100, page = page)
      }
    } catch (error: GitLabApiException) {
      // The /diffs endpoint arrived in GitLab 15.7; older servers only have /changes.
      if (error.code != 404) throw error
      val changes = service.getMergeRequestChanges(projectId, mrIid).requireBody().changes
      PagedSlice(items = changes, totalCount = changes.size, truncated = false)
    }
  }

  suspend fun getPipelines(instance: GitLabInstance, projectId: Long, perPage: Int = 20): Result<List<GitLabPipeline>> =
    getPipelinesPage(instance, projectId, perPage = perPage, page = 1).map { it.items }

  suspend fun getPipelinesPage(
    instance: GitLabInstance,
    projectId: Long,
    perPage: Int = 20,
    page: Int = 1,
  ): Result<ListPage<GitLabPipeline>> = call {
    val response = service(instance).getProjectPipelines(projectId, perPage = perPage, page = page)
    if (!response.isSuccessful) throw response.toGitLabException()
    ListPage(
      items = response.body().orEmpty().map { it.withDisplayTimes() },
      nextPage = nextPage(response, page),
      totalCount = response.headers()["X-Total"]?.toIntOrNull(),
    )
  }

  /**
   * Recent pipelines across the most recently active projects. Bounded to [projectLimit] projects,
   * a handful of pipelines each, fetched four at a time.
   */
  suspend fun getRecentPipelines(
    instance: GitLabInstance,
    projects: List<GitLabProject>,
    projectLimit: Int = 8,
    total: Int = 20
  ): Result<List<GitLabPipeline>> =
    getRecentPipelinesPage(instance, projects, projectLimit = projectLimit, total = total).map { it.items }

  suspend fun getRecentPipelinesPage(
    instance: GitLabInstance,
    projects: List<GitLabProject>,
    projectLimit: Int = 8,
    total: Int = 20
  ): Result<PagedSlice<GitLabPipeline>> = call {
    if (projects.isEmpty()) return@call PagedSlice(emptyList())
    val service = service(instance)
    val slots = Semaphore(4)
    val candidates = projects
      .sortedByDescending { parseGitLabMillis(it.lastActivityRaw) ?: 0L }
      .take(projectLimit)
    val hasMore = java.util.concurrent.atomic.AtomicBoolean(projects.size > projectLimit)
    val listed = coroutineScope {
      candidates.map { project ->
        async {
          slots.withPermit {
            try {
              val response = service.getProjectPipelines(project.id, perPage = 5)
              if (response.headers()["X-Next-Page"]?.toIntOrNull()?.let { it > 1 } == true) {
                hasMore.set(true)
              }
              response.requireList()
            } catch (error: GitLabApiException) {
              // Projects without CI access answer 403; skip them rather than fail the list.
              emptyList()
            }
          }
        }
      }.awaitAll().flatten()
    }
    val sorted = listed.sortedByDescending { parseGitLabMillis(it.createdAt) ?: it.id }
    if (sorted.size > total) hasMore.set(true)
    val recent = sorted.take(total)
    PagedSlice(
      items = attachStages(service, recent.map { it.withDisplayTimes() }, limit = 6),
      truncated = hasMore.get(),
    )
  }

  suspend fun attachJobs(
    instance: GitLabInstance,
    pipelines: List<GitLabPipeline>,
    limit: Int
  ): List<GitLabPipeline> = withContext(Dispatchers.IO) {
    attachStages(service(instance), pipelines, limit)
  }

  private suspend fun attachStages(service: GitLabApiService, pipelines: List<GitLabPipeline>, limit: Int): List<GitLabPipeline> {
    if (pipelines.isEmpty() || limit <= 0) return pipelines
    val slots = Semaphore(4)
    return coroutineScope {
      pipelines.mapIndexed { index, pipeline ->
        async {
          if (index >= limit || pipeline.projectId == 0L) {
            pipeline
          } else {
            val jobs = slots.withPermit {
              try {
                service.getPipelineJobs(pipeline.projectId, pipeline.id).requireList()
              } catch (cancel: CancellationException) {
                throw cancel
              } catch (_: Exception) {
                emptyList()
              }
            }
            pipeline.copy(stages = stagesFromJobs(jobs))
          }
        }
      }.awaitAll()
    }
  }

  suspend fun retryPipeline(
    instance: GitLabInstance,
    projectId: Long,
    pipelineId: Long
  ): Result<GitLabPipeline> = call {
    requireToken(instance)
    val service = service(instance)
    val retried = service.retryPipeline(projectId, pipelineId).requireBody().withDisplayTimes()
    attachStages(service, listOf(retried), limit = 1).first()
  }

  suspend fun getRepositoryTree(
    instance: GitLabInstance,
    projectId: Long,
    path: String?,
    ref: String?
  ): Result<List<GitLabTreeItem>> = getRepositoryTreePage(instance, projectId, path, ref).map { it.items }

  suspend fun getRepositoryTreePage(
    instance: GitLabInstance,
    projectId: Long,
    path: String?,
    ref: String?
  ): Result<PagedSlice<GitLabTreeItem>> = call {
    val normalizedPath = path?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    val slice = fetchSlice(maxPages = MAX_TREE_PAGES) { page ->
      service(instance).getRepositoryTree(projectId, path = normalizedPath, ref = ref, perPage = 100, page = page)
    }
    slice.copy(
      items = slice.items.sortedWith(compareBy<GitLabTreeItem> { if (it.type == "tree") 0 else 1 }.thenBy { it.name.lowercase() }),
    )
  }

  suspend fun getRepositoryCommits(
    instance: GitLabInstance,
    projectId: Long,
    ref: String?
  ): Result<List<GitLabCommit>> = getCommits(instance, projectId, ref)

  suspend fun getFileContent(
    instance: GitLabInstance,
    projectId: Long,
    filePath: String,
    ref: String?
  ): Result<String> = call {
    val response = service(instance).getRawFileContent(projectId, encodeGitLabPath(filePath), ref ?: "HEAD")
    if (response.code() == 404) {
      response.errorBody()?.close()
      val targetRef = ref?.takeIf { it.isNotBlank() }?.let { "on \"$it\"" } ?: "on the default branch"
      throw GitLabApiException(404, "This file isn't $targetRef anymore. Go back and refresh the file list.")
    }
    if (!response.isSuccessful) throw response.toGitLabException()
    decodeBody(response.body())
  }

  suspend fun getReadme(instance: GitLabInstance, projectId: Long, ref: String?): Result<String?> = call {
    readReadme(service(instance), projectId, ref)
  }

  suspend fun getTodosFeed(
    instance: GitLabInstance,
    state: String? = null,
    action: String? = null,
    type: String? = null,
    projectId: Long? = null,
    authorId: Long? = null,
  ): Result<ServerFeed<GitLabTodo>> = call {
    if (instance.token.isBlank()) return@call ServerFeed(emptyList(), null)
    val service = service(instance)
    if (state != null) {
      val maxPages = if (state == "pending") 2 else 1
      val perPage = if (state == "pending") 100 else 30
      return@call fetchPagesWithServerTime(maxPages = maxPages) { page ->
        service.getTodos(
          state = state,
          action = action,
          type = type,
          projectId = projectId,
          authorId = authorId,
          perPage = perPage,
          page = page,
        )
      }
    }
    coroutineScope {
      val pending = async {
        fetchPagesWithServerTime(maxPages = 2) { page ->
          service.getTodos(
            state = "pending",
            action = action,
            type = type,
            projectId = projectId,
            authorId = authorId,
            perPage = 100,
            page = page,
          )
        }
      }
      val done = async {
        runCatchingApi {
          fetchPagesWithServerTime(maxPages = 1) { page ->
            service.getTodos(
              state = "done",
              action = action,
              type = type,
              projectId = projectId,
              authorId = authorId,
              perPage = 30,
              page = page,
            )
          }
        }.getOrDefault(ServerFeed(emptyList(), null))
      }
      val pendingFeed = pending.await()
      val doneFeed = done.await()
      val serverTime = listOfNotNull(pendingFeed.serverTimeMs, doneFeed.serverTimeMs).maxOrNull()
      ServerFeed((pendingFeed.items + doneFeed.items).distinctBy { it.id }, serverTime)
    }
  }

  suspend fun getTodos(
    instance: GitLabInstance,
    state: String? = null,
    action: String? = null,
    type: String? = null,
    projectId: Long? = null,
    authorId: Long? = null,
  ): Result<List<GitLabTodo>> = getTodosFeed(
    instance = instance,
    state = state,
    action = action,
    type = type,
    projectId = projectId,
    authorId = authorId,
  ).map { it.items }

  suspend fun markTodoDone(instance: GitLabInstance, todoId: Long): Result<Unit> = call {
    requireToken(instance)
    val response = service(instance).markTodoAsDone(todoId)
    response.body()?.close()
    if (!response.isSuccessful) throw response.toGitLabException()
  }

  suspend fun markAllTodosDone(instance: GitLabInstance): Result<Unit> = call {
    requireToken(instance)
    val response = service(instance).markAllTodosAsDone()
    response.body()?.close()
    if (!response.isSuccessful) throw response.toGitLabException()
  }

  // ---------------------------------------------------------------------------------------------
  // Project metadata
  // ---------------------------------------------------------------------------------------------

  suspend fun getProjectWithLicense(instance: GitLabInstance, projectId: Long): Result<GitLabProject> = call {
    service(instance).getProject(projectId.toString(), license = true).requireBody().withDisplayTimes()
  }

  suspend fun getProjectByPath(instance: GitLabInstance, path: String): Result<GitLabProject> = call {
    service(instance).getProject(encodeGitLabPath(path)).requireBody().withDisplayTimes()
  }

  /** Language shares in percent, largest first. */
  suspend fun getLanguages(instance: GitLabInstance, projectId: Long): Result<List<Pair<String, Double>>> = call {
    service(instance).getLanguages(projectId).requireBody().entries.map { it.key to it.value }.sortedByDescending { it.second }
  }

  suspend fun getBranches(instance: GitLabInstance, projectId: Long, search: String? = null): Result<List<GitLabBranch>> = call {
    // Most recently updated first, as GitHub shows them; the default branch is pinned below.
    fetchPages(maxPages = 1) { page -> service(instance).getBranches(projectId, search?.takeIf { it.isNotBlank() }, page = page) }
      .sortedWith(compareByDescending<GitLabBranch> { it.default }.thenByDescending { parseGitLabMillis(it.commit?.committedDate ?: "") ?: 0L })
      .map { it.copy(commit = it.commit?.withDisplayTimes()) }
  }

  suspend fun getTags(instance: GitLabInstance, projectId: Long, search: String? = null): Result<List<GitLabTag>> = call {
    fetchPages(maxPages = 1) { page -> service(instance).getTags(projectId, search = search?.takeIf { it.isNotBlank() }, page = page) }
      .map { it.copy(commit = it.commit?.withDisplayTimes()) }
  }

  suspend fun getReleases(instance: GitLabInstance, projectId: Long): Result<List<GitLabRelease>> = call {
    try {
      fetchPages(maxPages = 1) { page -> service(instance).getReleases(projectId, page = page) }
        .map { it.copy(releasedAt = it.releasedAt?.let(::formatGitLabTime), createdAt = it.createdAt?.let(::formatGitLabTime), commit = it.commit?.withDisplayTimes()) }
    } catch (error: GitLabApiException) {
      // Projects that turned the Releases feature off answer 403: that reads as "no releases".
      if (error.code == 403) emptyList() else throw error
    }
  }

  suspend fun getContributors(instance: GitLabInstance, projectId: Long): Result<List<GitLabContributor>> = call {
    val response = service(instance).getContributors(projectId)
    // Contributor stats need repository access; public projects sometimes answer 403.
    if (response.code() == 403) return@call emptyList()
    val people = response.requireList()
    kotlinx.coroutines.withTimeoutOrNull(5_000) {
      coroutineScope {
        people.mapIndexed { index, person ->
          async {
            val avatar = if (index < MAX_CONTRIBUTOR_AVATARS) {
              person.avatarUrl ?: contributorAvatar(instance, person.email)
            } else {
              person.avatarUrl
            }
            person.copy(avatarUrl = avatar)
          }
        }.awaitAll()
      }
    } ?: people
  }

  suspend fun getLabels(instance: GitLabInstance, projectId: Long): Result<List<GitLabLabel>> = call {
    fetchPages(maxPages = 3) { page -> service(instance).getLabels(projectId, page = page) }
  }

  suspend fun getProjectEvents(instance: GitLabInstance, projectId: Long): Result<List<GitLabEvent>> = call {
    service(instance).getProjectEvents(projectId).requireList().map { it.copy(createdAt = formatGitLabTime(it.createdAt)) }
  }

  suspend fun getCommits(instance: GitLabInstance, projectId: Long, ref: String?, path: String? = null): Result<List<GitLabCommit>> =
    getCommitsPage(instance, projectId, ref, path = path, page = 1).map { it.items }

  suspend fun getCommitsPage(
    instance: GitLabInstance,
    projectId: Long,
    ref: String?,
    path: String? = null,
    page: Int = 1,
  ): Result<ListPage<GitLabCommit>> = call {
    val response = service(instance).getRepositoryCommits(projectId, ref = ref, path = path, perPage = 40, page = page)
    if (!response.isSuccessful) throw response.toGitLabException()
    ListPage(
      items = response.body().orEmpty().map { it.withDisplayTimes() },
      nextPage = nextPage(response, page),
      totalCount = response.headers()["X-Total"]?.toIntOrNull(),
    )
  }

  suspend fun getCommit(instance: GitLabInstance, projectId: Long, sha: String): Result<GitLabCommit> = call {
    service(instance).getCommit(projectId, sha).requireBody().withDisplayTimes()
  }

  suspend fun getCommitDiff(instance: GitLabInstance, projectId: Long, sha: String): Result<List<GitLabFileDiff>> =
    getCommitDiffPage(instance, projectId, sha).map { it.items }

  suspend fun getCommitDiffPage(instance: GitLabInstance, projectId: Long, sha: String): Result<PagedSlice<GitLabFileDiff>> = call {
    fetchSlice(maxPages = MAX_COMMIT_DIFF_PAGES) { page -> service(instance).getCommitDiff(projectId, sha, page = page) }
  }

  // ---------------------------------------------------------------------------------------------
  // Merge request details
  // ---------------------------------------------------------------------------------------------

  suspend fun getMergeRequestDiscussions(instance: GitLabInstance, projectId: Long, iid: Long): Result<List<com.cboyxd.gitlab.data.model.GitLabDiscussion>> =
    getMergeRequestDiscussionsPage(instance, projectId, iid).map { it.items }

  suspend fun getMergeRequestDiscussionsPage(
    instance: GitLabInstance,
    projectId: Long,
    iid: Long,
  ): Result<PagedSlice<com.cboyxd.gitlab.data.model.GitLabDiscussion>> = call {
    val slice = fetchSlice(maxPages = MAX_DISCUSSION_PAGES) { page ->
      service(instance).getMergeRequestDiscussions(projectId, iid, page = page)
    }
    val sorted = slice.items
      .sortedBy { it.notes.firstOrNull()?.createdAt.orEmpty() }
      .map { discussion -> discussion.copy(notes = discussion.notes.sortedBy { it.createdAt }.map { it.withDisplayTimes() }) }
    slice.copy(items = sorted)
  }

  /** Compare the stored commits, so older threads never show unrelated code from the latest revision. */
  suspend fun getDiscussionDiffs(instance: GitLabInstance, projectId: Long, discussions: List<com.cboyxd.gitlab.data.model.GitLabDiscussion>): Map<String, GitLabFileDiff> = coroutineScope {
    val positioned = discussions.mapNotNull { d -> d.notes.firstOrNull { it.position != null }?.position?.let { d.id to it } }
    val gate = Semaphore(3)
    positioned.groupBy { (_, p) -> p.baseSha to p.headSha }.map { (revision, threads) -> async {
      gate.withPermit {
        val files = if (revision.first.isBlank() || revision.second.isBlank()) emptyList() else call {
          service(instance).compareDiscussionRevision(projectId, revision.first, revision.second).requireBody().diffs
        }.getOrDefault(emptyList())
        threads.mapNotNull { (id, p) -> files.firstOrNull { it.oldPath == p.oldPath && it.newPath == p.newPath }?.let { id to it } }
      }
    } }.awaitAll().flatten().toMap()
  }

  suspend fun getMergeRequestNotes(instance: GitLabInstance, projectId: Long, iid: Long): Result<List<GitLabNote>> = call {
    fetchPages(maxPages = 5) { page -> service(instance).getMergeRequestNotes(projectId, iid, page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun replyToDiscussion(instance: GitLabInstance, projectId: Long, iid: Long, discussionId: String, body: String): Result<GitLabNote> = call {
    requireToken(instance)
    if (body.isBlank()) throw GitLabApiException(400, "Write a reply first.")
    service(instance).replyToMergeRequestDiscussion(projectId, iid, discussionId, body).requireBody().withDisplayTimes()
  }

  /** Starts a thread on one diff line of the version [refs] names. */
  suspend fun createDiffNote(
    instance: GitLabInstance, projectId: Long, iid: Long, refs: GitLabDiffRefs, anchor: DiffLineAnchor, body: String,
  ): Result<GitLabDiscussion> = call {
    requireToken(instance)
    if (body.isBlank()) throw GitLabApiException(400, "Write a comment first.")
    val response = service(instance).createMergeRequestDiscussion(projectId, iid, body, diffNotePosition(refs, anchor))
    when (response.code()) {
      // GitLab rejects a position that no longer matches the changes, e.g. after a new push.
      400 -> throw GitLabApiException(400, "GitLab couldn't place a comment on that line. The changes may have been updated; refresh and try again.")
      403 -> throw GitLabApiException(403, "You can't comment on this merge request.")
    }
    response.requireBody().let { d -> d.copy(notes = d.notes.map { it.withDisplayTimes() }) }
  }

  suspend fun resolveDiscussion(instance: GitLabInstance, projectId: Long, iid: Long, discussionId: String, resolved: Boolean): Result<GitLabDiscussion> = call {
    requireToken(instance)
    val response = service(instance).resolveMergeRequestDiscussion(projectId, iid, discussionId, resolved)
    if (response.code() == 403) throw GitLabApiException(403, "You can't resolve threads in this merge request.")
    response.requireBody().let { d -> d.copy(notes = d.notes.map { it.withDisplayTimes() }) }
  }

  suspend fun createMergeRequestNote(instance: GitLabInstance, projectId: Long, iid: Long, body: String): Result<GitLabNote> = call {
    requireToken(instance)
    service(instance).createMergeRequestNote(projectId, iid, body).requireBody().withDisplayTimes()
  }

  suspend fun getMergeRequestCommits(instance: GitLabInstance, projectId: Long, iid: Long): Result<List<GitLabCommit>> = call {
    fetchPages(maxPages = 2) { page -> service(instance).getMergeRequestCommits(projectId, iid, page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun getMergeRequestPipelines(instance: GitLabInstance, projectId: Long, iid: Long): Result<List<GitLabPipeline>> = call {
    val response = service(instance).getMergeRequestPipelines(projectId, iid)
    if (response.code() == 403) return@call emptyList()
    response.requireList().map { it.withDisplayTimes() }
  }

  /** Approval state, or null when the server has no approvals API for this request (CE, anonymous). */
  /** Approves the reviewed commit of [mr]. */
  suspend fun approveMergeRequest(instance: GitLabInstance, mr: GitLabMergeRequest): Result<GitLabApprovals> = call {
    requireToken(instance)
    val response = service(instance).approveMergeRequest(mr.projectId, mr.iid, sha = mr.sha?.takeIf { it.isNotBlank() })
    when (response.code()) {
      401, 403 -> throw GitLabApiException(response.code(), "You can't approve this merge request. Authors and people without the right role often can't.")
      409 -> throw GitLabApiException(409, "The source branch changed since you opened this merge request. Refresh it and review the new commits first.")
    }
    response.requireBody()
  }

  suspend fun unapproveMergeRequest(instance: GitLabInstance, mr: GitLabMergeRequest): Result<Unit> = call {
    requireToken(instance)
    val response = service(instance).unapproveMergeRequest(mr.projectId, mr.iid)
    // 404: there was no approval of ours to revoke; the result is the same.
    if (!response.isSuccessful && response.code() != 404) throw response.toGitLabException()
    response.body()?.close()
    response.errorBody()?.close()
  }

  suspend fun getMergeRequestApprovals(instance: GitLabInstance, projectId: Long, iid: Long): Result<GitLabApprovals?> = call {
    val response = service(instance).getMergeRequestApprovals(projectId, iid)
    if (response.code() == 401 || response.code() == 403 || response.code() == 404) return@call null
    response.requireBody()
  }

  // ---------------------------------------------------------------------------------------------
  // Pipelines and jobs
  // ---------------------------------------------------------------------------------------------

  suspend fun getPipeline(instance: GitLabInstance, projectId: Long, pipelineId: Long): Result<GitLabPipeline> = call {
    service(instance).getPipeline(projectId, pipelineId).requireBody().withDisplayTimes()
  }

  suspend fun getPipelineJobs(instance: GitLabInstance, projectId: Long, pipelineId: Long): Result<List<GitLabJob>> = call {
    // Large pipelines have hundreds of jobs; the API pages them newest first.
    fetchPages(maxPages = 5) { page -> service(instance).getPipelineJobs(projectId, pipelineId, page = page) }.sortedBy { it.id }
  }

  /** Runs a new pipeline on [ref]. GitLab's own reason is shown when it refuses (no jobs, unknown ref). */
  suspend fun runPipeline(instance: GitLabInstance, projectId: Long, ref: String): Result<GitLabPipeline> = call {
    requireToken(instance)
    if (ref.isBlank()) throw GitLabApiException(400, "Enter a branch or tag.")
    val response = service(instance).createPipeline(projectId, ref.trim())
    if (response.code() == 403) throw GitLabApiException(403, "You can't run pipelines in this project.")
    response.requireBody()
  }

  suspend fun cancelPipeline(instance: GitLabInstance, projectId: Long, pipelineId: Long): Result<GitLabPipeline> = call {
    requireToken(instance)
    service(instance).cancelPipeline(projectId, pipelineId).requireBody().withDisplayTimes()
  }

  suspend fun getJob(instance: GitLabInstance, projectId: Long, jobId: Long): Result<GitLabJob> = call {
    service(instance).getJob(projectId, jobId).requireBody()
  }

  suspend fun retryJob(instance: GitLabInstance, projectId: Long, jobId: Long): Result<GitLabJob> = call {
    requireToken(instance)
    service(instance).retryJob(projectId, jobId).requireBody()
  }

  /**
   * Copies the job's artifacts archive into [open]'s stream and returns the bytes written. The
   * body is streamed, so archives far larger than memory are fine.
   */
  suspend fun downloadJobArtifacts(instance: GitLabInstance, projectId: Long, jobId: Long, open: () -> java.io.OutputStream?): Result<Long> = call {
    val response = GitLabApiClient.createDownloadService(instance).downloadJobArtifacts(projectId, jobId)
    if (response.code() == 404) {
      response.errorBody()?.close()
      throw GitLabApiException(404, "This job has no artifacts any more. They may have expired.")
    }
    if (!response.isSuccessful) throw response.toGitLabException()
    val body = response.body() ?: throw GitLabApiException(response.code(), "The server sent no archive.")
    body.use { b ->
      val out = open() ?: throw GitLabApiException(0, "Couldn't open the file to save to.")
      out.use { b.byteStream().copyTo(it) }
    }
  }

  /** Runs a manual job. Protected environments and branches can refuse (403). */
  suspend fun playJob(instance: GitLabInstance, projectId: Long, jobId: Long): Result<GitLabJob> = call {
    requireToken(instance)
    val response = service(instance).playJob(projectId, jobId)
    when (response.code()) {
      403 -> throw GitLabApiException(403, "You can't run this job. It may deploy to a protected environment or branch.")
      400 -> throw GitLabApiException(400, "This job can't be started now. It may already have run.")
    }
    response.requireBody()
  }

  suspend fun cancelJob(instance: GitLabInstance, projectId: Long, jobId: Long): Result<GitLabJob> = call {
    requireToken(instance)
    val response = service(instance).cancelJob(projectId, jobId)
    if (response.code() == 403) throw GitLabApiException(403, "You can't cancel this job.")
    response.requireBody()
  }

  /**
   * The end of a job log. Logs can be hundreds of megabytes, so only the last [MAX_TRACE_BYTES]
   * are requested (Range) or, when the server sends everything, kept while streaming.
   */
  suspend fun getJobTrace(instance: GitLabInstance, projectId: Long, jobId: Long): Result<JobTrace> = call {
    val response = service(instance).getJobTrace(projectId, jobId, range = "bytes=-$MAX_TRACE_BYTES")
    // 416: the log is empty, so no suffix range can be satisfied.
    if (response.code() == 416) {
      response.errorBody()?.close()
      return@call JobTrace("", truncated = false)
    }
    if (!response.isSuccessful) throw response.toGitLabException()
    val rangeStart = if (response.code() == 206) contentRangeStart(response.headers()["Content-Range"]) ?: 0L else 0L
    // A 206 that doesn't start at byte 0 is a tail of a longer log.
    val startsMidway = response.code() == 206 && rangeStart > 0L
    val body = response.body() ?: return@call JobTrace("", truncated = false)
    body.use {
      val source = it.source()
      val buffer = Buffer()
      var skippedBytes = 0L
      var skippedNewlines = 0
      fun dropPrefix(count: Long) {
        if (count <= 0L) return
        if (!startsMidway) skippedNewlines += countNewlines(buffer, count)
        buffer.skip(count)
        skippedBytes += count
      }
      while (source.read(buffer, 64 * 1024L) != -1L) {
        if (buffer.size > MAX_TRACE_BYTES * 2) {
          dropPrefix(buffer.size - MAX_TRACE_BYTES)
        }
      }
      if (buffer.size > MAX_TRACE_BYTES) {
        dropPrefix(buffer.size - MAX_TRACE_BYTES)
      }
      val truncated = skippedBytes > 0L || startsMidway
      // A cut tail starts mid-line, possibly inside a UTF-8 character or an ANSI escape: start
      // at the next full line instead.
      if (truncated) {
        val newline = buffer.indexOf('\n'.code.toByte())
        if (newline >= 0) {
          dropPrefix(newline + 1)
        } else {
          // No newline in the whole window (one giant line): skip any split UTF-8 continuation bytes.
          var continuation = 0L
          while (continuation < buffer.size && continuation < 4L && (buffer[continuation].toInt() and 0xC0) == 0x80) {
            continuation++
          }
          if (continuation > 0L) {
            buffer.skip(continuation)
            skippedBytes += continuation
          }
        }
      }
      JobTrace(
        text = buffer.readUtf8(),
        truncated = truncated,
        startOffset = rangeStart + skippedBytes,
        earlierLines = if (truncated && !startsMidway) skippedNewlines else null,
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // People, groups, search, activity
  // ---------------------------------------------------------------------------------------------

  suspend fun getUser(instance: GitLabInstance, userId: Long): Result<GitLabUser> = call {
    service(instance).getUser(userId).requireBody()
  }

  suspend fun findUser(instance: GitLabInstance, username: String): Result<GitLabUser?> = call {
    service(instance).getUsersByName(username = username).requireList().firstOrNull()
  }

  suspend fun searchUsers(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabUser>> = call {
    if (projectId != null) {
      service(instance).searchProjectUsers(projectId, query).requireList()
    } else {
      service(instance).getUsersByName(search = query).requireList()
    }
  }

  suspend fun getUserProjects(instance: GitLabInstance, userId: Long): Result<List<GitLabProject>> = call {
    service(instance).getUserProjects(userId).requireList().map { it.withDisplayTimes() }
  }

  suspend fun getUserEvents(instance: GitLabInstance, userId: Long): Result<List<GitLabEvent>> = call {
    val response = service(instance).getUserEvents(userId)
    // Private profiles hide their activity.
    if (response.code() == 403 || response.code() == 404) return@call emptyList()
    response.requireList().map { it.copy(createdAt = formatGitLabTime(it.createdAt)) }
  }

  suspend fun getMyEvents(instance: GitLabInstance): Result<List<GitLabEvent>> = call {
    if (instance.token.isBlank()) return@call emptyList()
    service(instance).getMyEvents().requireList().map { it.copy(createdAt = formatGitLabTime(it.createdAt)) }
  }

  suspend fun getGroup(instance: GitLabInstance, groupPath: String): Result<GitLabGroup> = call {
    service(instance).getGroup(encodeGitLabPath(groupPath)).requireBody()
  }

  suspend fun getNamespace(instance: GitLabInstance, path: String): Result<GitLabNamespace> = call {
    service(instance).getNamespace(encodeGitLabPath(path)).requireBody()
  }

  suspend fun getSubgroups(instance: GitLabInstance, groupPath: String): Result<List<GitLabGroup>> = call {
    fetchPages(maxPages = 1) { page -> service(instance).getSubgroups(encodeGitLabPath(groupPath), page = page) }
  }

  suspend fun getProjectMembers(instance: GitLabInstance, projectId: Long, query: String? = null): Result<List<GitLabUser>> = call {
    fetchPages(maxPages = 2) { page ->
      service(instance).getProjectMembers(projectId, query = query?.takeIf { it.isNotBlank() }, page = page)
    }.distinctBy { it.id }
  }

  suspend fun getProjectMilestones(instance: GitLabInstance, projectId: Long): Result<List<GitLabMilestone>> = call {
    fetchPages(maxPages = 2) { page ->
      service(instance).getProjectMilestones(projectId, page = page)
    }
  }

  suspend fun searchIssues(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabIssue>> = call {
    if (projectId != null) {
      service(instance).searchProjectIssues(projectId, query).requireList().map { it.withDisplayTimes() }
    } else {
      requireToken(instance)
      service(instance).searchIssues(query).requireList().map { it.withDisplayTimes() }
    }
  }

  suspend fun searchMergeRequests(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabMergeRequest>> = call {
    if (projectId != null) {
      service(instance).searchProjectMergeRequests(projectId, query).requireList().map { it.withDisplayTimes() }
    } else {
      requireToken(instance)
      service(instance).searchMergeRequests(query).requireList().map { it.withDisplayTimes() }
    }
  }

  suspend fun searchBlobs(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabBlobSearchResult>> = call {
    if (projectId != null) {
      service(instance).searchProjectBlobs(projectId, query).requireList()
    } else {
      requireToken(instance)
      service(instance).searchBlobs(query).requireList()
    }
  }

  suspend fun searchCommits(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabCommit>> = call {
    val items = if (projectId != null) {
      service(instance).searchProjectCommits(projectId, query).requireList()
    } else {
      requireToken(instance)
      service(instance).searchCommits(query).requireList()
    }
    items.map { c -> c.copy(projectId = c.projectId ?: projectId).withDisplayTimes() }
  }

  suspend fun searchNotes(instance: GitLabInstance, query: String, projectId: Long? = null): Result<List<GitLabNoteSearchResult>> = call {
    val items = if (projectId != null) {
      service(instance).searchProjectNotes(projectId, query).requireList()
    } else {
      requireToken(instance)
      service(instance).searchNotes(query).requireList()
    }
    items.map { n ->
      n.copy(
        projectId = if (n.projectId > 0) n.projectId else (projectId ?: 0L),
        createdAt = formatGitLabTime(n.createdAt),
      )
    }
  }

  suspend fun getIssueAwardEmojis(instance: GitLabInstance, projectId: Long, issueIid: Long): Result<List<GitLabAwardEmoji>> = call {
    service(instance).getIssueAwardEmojis(projectId, issueIid).requireList()
  }

  suspend fun addIssueAwardEmoji(instance: GitLabInstance, projectId: Long, issueIid: Long, name: String): Result<GitLabAwardEmoji> = call {
    requireToken(instance)
    service(instance).addIssueAwardEmoji(projectId, issueIid, name).requireBody()
  }

  suspend fun deleteIssueAwardEmoji(instance: GitLabInstance, projectId: Long, issueIid: Long, awardId: Long): Result<Unit> = call {
    requireToken(instance)
    deleteAward { service(instance).deleteIssueAwardEmoji(projectId, issueIid, awardId) }
  }

  suspend fun getMergeRequestAwardEmojis(instance: GitLabInstance, projectId: Long, mrIid: Long): Result<List<GitLabAwardEmoji>> = call {
    service(instance).getMergeRequestAwardEmojis(projectId, mrIid).requireList()
  }

  suspend fun addMergeRequestAwardEmoji(instance: GitLabInstance, projectId: Long, mrIid: Long, name: String): Result<GitLabAwardEmoji> = call {
    requireToken(instance)
    service(instance).addMergeRequestAwardEmoji(projectId, mrIid, name).requireBody()
  }

  suspend fun deleteMergeRequestAwardEmoji(instance: GitLabInstance, projectId: Long, mrIid: Long, awardId: Long): Result<Unit> = call {
    requireToken(instance)
    deleteAward { service(instance).deleteMergeRequestAwardEmoji(projectId, mrIid, awardId) }
  }

  suspend fun getIssueNoteAwardEmojis(instance: GitLabInstance, projectId: Long, issueIid: Long, noteId: Long): Result<List<GitLabAwardEmoji>> = call {
    service(instance).getIssueNoteAwardEmojis(projectId, issueIid, noteId).requireList()
  }

  suspend fun addIssueNoteAwardEmoji(instance: GitLabInstance, projectId: Long, issueIid: Long, noteId: Long, name: String): Result<GitLabAwardEmoji> = call {
    requireToken(instance)
    service(instance).addIssueNoteAwardEmoji(projectId, issueIid, noteId, name).requireBody()
  }

  suspend fun deleteIssueNoteAwardEmoji(instance: GitLabInstance, projectId: Long, issueIid: Long, noteId: Long, awardId: Long): Result<Unit> = call {
    requireToken(instance)
    deleteAward { service(instance).deleteIssueNoteAwardEmoji(projectId, issueIid, noteId, awardId) }
  }

  suspend fun getMergeRequestNoteAwardEmojis(instance: GitLabInstance, projectId: Long, mrIid: Long, noteId: Long): Result<List<GitLabAwardEmoji>> = call {
    service(instance).getMergeRequestNoteAwardEmojis(projectId, mrIid, noteId).requireList()
  }

  suspend fun addMergeRequestNoteAwardEmoji(instance: GitLabInstance, projectId: Long, mrIid: Long, noteId: Long, name: String): Result<GitLabAwardEmoji> = call {
    requireToken(instance)
    service(instance).addMergeRequestNoteAwardEmoji(projectId, mrIid, noteId, name).requireBody()
  }

  suspend fun deleteMergeRequestNoteAwardEmoji(instance: GitLabInstance, projectId: Long, mrIid: Long, noteId: Long, awardId: Long): Result<Unit> = call {
    requireToken(instance)
    deleteAward { service(instance).deleteMergeRequestNoteAwardEmoji(projectId, mrIid, noteId, awardId) }
  }

  private suspend fun deleteAward(request: suspend () -> Response<ResponseBody>) {
    val response = request()
    if (!response.isSuccessful && response.code() != 404) throw response.toGitLabException()
    response.body()?.close()
    response.errorBody()?.close()
  }

  private suspend fun versionOrBlank(service: GitLabApiService): String = try {
    service.getVersion().let { response -> if (response.isSuccessful) response.body()?.version.orEmpty() else "" }
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (_: Exception) {
    ""
  }

  private suspend fun readReadme(
    service: GitLabApiService,
    projectId: Long,
    ref: String?
  ): String? {
    for (name in README_FILES) {
      val response = service.getRawFileContent(projectId, encodeGitLabPath(name), ref ?: "HEAD")
      if (response.code() == 404) {
        response.errorBody()?.close()
        continue
      }
      if (!response.isSuccessful) throw response.toGitLabException()
      return decodeBody(response.body())
    }
    return null
  }

  private fun decodeBody(body: ResponseBody?): String {
    if (body == null) return ""
    return body.use { responseBody ->
      val source = responseBody.source()
      val buffer = Buffer()
      // read() returns at most one segment; keep reading until the cap or end of stream.
      while (buffer.size <= MAX_FILE_BYTES) {
        if (source.read(buffer, MAX_FILE_BYTES + 1 - buffer.size) == -1L) break
      }
      if (buffer.size == 0L) return@use ""
      val truncated = buffer.size > MAX_FILE_BYTES
      val byteCount = if (truncated) MAX_FILE_BYTES else buffer.size
      val bytes = buffer.readByteArray(byteCount)
      decodeFileBytes(bytes, truncated)
    }
  }

  private suspend fun <T> fetchPages(
    maxPages: Int,
    request: suspend (page: Int) -> Response<List<T>>
  ): List<T> = fetchSlice(maxPages, request).items

  private suspend fun <T> fetchSlice(
    maxPages: Int,
    request: suspend (page: Int) -> Response<List<T>>
  ): PagedSlice<T> {
    val all = mutableListOf<T>()
    var page = 1
    var total: Int? = null
    for (attempt in 0 until maxPages) {
      val response = request(page)
      if (!response.isSuccessful) throw response.toGitLabException()
      if (total == null) {
        total = response.headers()["X-Total"]?.toIntOrNull()
      }
      val chunk = response.body().orEmpty()
      all += chunk
      val next = response.headers()["X-Next-Page"]?.toIntOrNull()
      if (next == null || next <= page || chunk.isEmpty()) {
        return PagedSlice(items = all, totalCount = total, truncated = false)
      }
      if (attempt == maxPages - 1) {
        return PagedSlice(items = all, totalCount = total, truncated = true)
      }
      page = next
    }
    return PagedSlice(items = all, totalCount = total, truncated = false)
  }

  private suspend fun <T> fetchPagesWithServerTime(
    maxPages: Int,
    request: suspend (page: Int) -> Response<List<T>>
  ): ServerFeed<T> {
    val all = mutableListOf<T>()
    var serverTimeMs: Long? = null
    var page = 1
    for (attempt in 0 until maxPages) {
      val response = request(page)
      if (!response.isSuccessful) throw response.toGitLabException()
      response.headers().getDate("Date")?.time?.let { dateMs ->
        serverTimeMs = maxOf(serverTimeMs ?: Long.MIN_VALUE, dateMs)
      }
      val chunk = response.body().orEmpty()
      all += chunk
      val next = response.headers()["X-Next-Page"]?.toIntOrNull()
      if (next == null || next <= page || chunk.isEmpty()) return ServerFeed(all, serverTimeMs)
      page = next
    }
    return ServerFeed(all, serverTimeMs)
  }

  /** Converts expected failures into Result; cancellation and fatal JVM errors propagate. */
  private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
      Result.success(block())
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      Result.failure(error.toApiException())
    }
  }

  /** One half of [getProjectCounts]: any failure (network, parsing, HTTP) only blanks that count. */
  private suspend fun <T> countOrNullValue(block: suspend () -> T?): T? = try {
    block()
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (_: Exception) {
    null
  }

  private suspend fun countOrNull(block: suspend () -> Int?): Int? = try {
    block()
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (_: Exception) {
    null
  }

  private suspend fun <T> runCatchingApi(block: suspend () -> T): Result<T> = try {
    Result.success(block())
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: GitLabApiException) {
    Result.failure(error)
  }

  private fun service(instance: GitLabInstance) = GitLabApiClient.createService(instance)

  private fun requireToken(instance: GitLabInstance) {
    if (instance.token.isBlank()) {
      throw GitLabApiException(401, "Sign in to this server to make changes.")
    }
  }

  private fun mergeProjects(member: List<GitLabProject>, starred: List<GitLabProject>): List<GitLabProject> {
    val starredIds = starred.map { it.id }.toSet()
    val merged = LinkedHashMap<Long, GitLabProject>()
    member.forEach { project ->
      merged[project.id] = project.copy(isStarred = project.isStarred || project.id in starredIds)
    }
    starred.forEach { project ->
      val existing = merged[project.id]
      merged[project.id] = (existing ?: project).copy(isStarred = true)
    }
    return merged.values.toList()
  }

  private fun <T> List<T>.newestFirst(time: (T) -> String): List<T> =
    sortedByDescending { parseGitLabMillis(time(it)) ?: 0L }

  private fun GitLabProject.withDisplayTimes() = copy(lastActivityAt = formatGitLabTime(lastActivityAt), lastActivityRaw = lastActivityAt)

  private fun GitLabIssue.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    updatedAt = formatGitLabTime(updatedAt),
    closedAt = closedAt?.let(::formatGitLabTime)
  )

  private fun GitLabMergeRequest.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    updatedAt = formatGitLabTime(updatedAt),
    mergedAt = mergedAt?.let(::formatGitLabTime),
    closedAt = closedAt?.let(::formatGitLabTime),
    headPipeline = headPipeline?.withDisplayTimes()
  )

  private fun GitLabPipeline.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    updatedAt = formatGitLabTime(updatedAt)
  )

  private fun GitLabCommit.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    committedDate = committedDate?.let(::formatGitLabTime)
  )

  private fun GitLabNote.withDisplayTimes() = copy(createdAt = formatGitLabTime(createdAt))

  private fun GitLabSnippet.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    updatedAt = formatGitLabTime(updatedAt),
  )

  private fun GitLabDeployment.withDisplayTimes() = copy(
    createdAt = formatGitLabTime(createdAt),
    updatedAt = formatGitLabTime(updatedAt),
  )

  private fun GitLabEnvironment.withDisplayTimes() = copy(
    updatedAt = updatedAt?.let(::formatGitLabTime),
    lastDeployment = lastDeployment?.withDisplayTimes(),
  )

  suspend fun getSnippets(instance: GitLabInstance): Result<List<GitLabSnippet>> = call {
    requireToken(instance)
    val api = service(instance)
    fetchPages(maxPages = 2) { page -> api.getSnippets(page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun getProjectSnippets(instance: GitLabInstance, projectId: Long): Result<List<GitLabSnippet>> = call {
    val api = service(instance)
    fetchPages(maxPages = 2) { page -> api.getProjectSnippets(projectId, page = page) }
      .map { it.withDisplayTimes() }
  }

  suspend fun getSnippet(
    instance: GitLabInstance,
    snippetId: Long,
    projectId: Long? = null,
  ): Result<GitLabSnippet> = call {
    val api = service(instance)
    val response = if (projectId != null && projectId > 0L) {
      api.getProjectSnippet(projectId, snippetId)
    } else {
      api.getSnippet(snippetId)
    }
    response.requireBody().withDisplayTimes()
  }

  suspend fun getSnippetRaw(
    instance: GitLabInstance,
    snippetId: Long,
    projectId: Long? = null,
  ): Result<String> = call {
    val api = service(instance)
    val response = if (projectId != null && projectId > 0L) {
      api.getProjectSnippetRaw(projectId, snippetId)
    } else {
      api.getSnippetRaw(snippetId)
    }
    if (!response.isSuccessful) throw response.toGitLabException()
    decodeBody(response.body())
  }

  suspend fun getProjectVariables(
    instance: GitLabInstance,
    projectId: Long,
  ): Result<List<GitLabCiVariable>> = call {
    requireToken(instance)
    val api = service(instance)
    try {
      fetchPages(maxPages = 2) { page -> api.getProjectVariables(projectId, page = page) }
        .sortedBy { it.key.lowercase() }
    } catch (e: GitLabApiException) {
      if (e.code == 403) {
        throw GitLabApiException(403, "You need Maintainer or Owner access to view CI/CD variables.")
      }
      throw e
    }
  }

  suspend fun getProjectEnvironments(
    instance: GitLabInstance,
    projectId: Long,
  ): Result<List<GitLabEnvironment>> = call {
    val api = service(instance)
    fetchPages(maxPages = 2) { page -> api.getProjectEnvironments(projectId, page = page) }
      .map { it.withDisplayTimes() }
  }

  private companion object {
    val README_FILES = listOf("README.md", "README.markdown", "README.rst", "README.txt", "README", "readme.md")
    const val MAX_FILE_BYTES = 400_000L
    const val MAX_TRACE_BYTES = 256_000L
    const val PROJECT_PAGE_SIZE = 50
    const val MAX_DIFF_PAGES = 5
    const val MAX_DISCUSSION_PAGES = 5
    const val MAX_TREE_PAGES = 5
    const val MAX_COMMIT_DIFF_PAGES = 4
    const val MAX_CONTRIBUTOR_AVATARS = 15
  }
}

private val DRAFT_PREFIX = Regex("""^\s*(?:(?:\[draft]|\(draft\)|draft:|draft\s+-|wip:)\s*)+""", RegexOption.IGNORE_CASE)

/** [title] without GitLab's draft markers ("Draft:", "[Draft]", "(Draft)", "Draft -", old "WIP:"). */
internal fun withoutDraftPrefix(title: String): String = title.replace(DRAFT_PREFIX, "").trim()

/** The first byte of a `Content-Range: bytes 1000-1999/2000` header, or null. */
internal fun contentRangeStart(header: String?): Long? =
  header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

private fun countNewlines(buffer: Buffer, upTo: Long): Int {
  var lines = 0
  var pos = 0L
  val limit = minOf(upTo, buffer.size)
  while (pos < limit) {
    val nl = buffer.indexOf('\n'.code.toByte(), pos, limit)
    if (nl < 0) break
    lines++
    pos = nl + 1
  }
  return lines
}

internal fun Throwable.toApiException(): Throwable = when (this) {
  is GitLabApiException -> this
  // JsonEncodingException is an IOException, so it must be matched before the network cases.
  is JsonDataException, is JsonEncodingException ->
    GitLabApiException(-1, "The server sent data this app can't read. Check that the URL points to a GitLab server.")
  is UnknownHostException -> GitLabApiException(0, "Unable to find the server. Check the address and your connection.")
  is SocketTimeoutException -> GitLabApiException(0, "The server took too long to respond. Try again.")
  is SSLException -> GitLabApiException(0, "Unable to open a secure connection. Check the server's HTTPS certificate.")
  is IOException -> GitLabApiException(0, "Unable to reach the server. Check your connection and try again.")
  is IllegalArgumentException -> GitLabApiException(-1, message ?: "Check the server address.")
  else -> GitLabApiException(-1, message ?: "Something went wrong. Try again.")
}

internal fun decodeFileBytes(bytes: ByteArray, truncated: Boolean): String {
  if (bytes.isEmpty()) return ""
  val sniff = bytes.size.coerceAtMost(8_000)
  if ((0 until sniff).any { bytes[it] == 0.toByte() }) {
    return "Binary file (${bytes.size} bytes). Open it on the web to view it."
  }
  val safeLength = if (truncated) utf8CompletePrefixLength(bytes) else bytes.size
  val text = String(bytes, 0, safeLength, Charsets.UTF_8)
    .replace("\r\n", "\n")
    .replace('\r', '\n')
  return if (truncated) text + "\n\n… The file is longer than 400 KB. Open it on the web to see the rest." else text
}

internal fun utf8CompletePrefixLength(bytes: ByteArray): Int {
  val len = bytes.size
  if (len == 0) return 0
  var i = len - 1
  while (i >= 0 && len - 1 - i < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
    i--
  }
  if (i < 0) return len
  val lead = bytes[i].toInt() and 0xFF
  val expectedLen = when {
    lead < 0x80 -> 1
    (lead and 0xE0) == 0xC0 -> 2
    (lead and 0xF0) == 0xE0 -> 3
    (lead and 0xF8) == 0xF0 -> 4
    else -> 1
  }
  return if (i + expectedLen > len) i else len
}
