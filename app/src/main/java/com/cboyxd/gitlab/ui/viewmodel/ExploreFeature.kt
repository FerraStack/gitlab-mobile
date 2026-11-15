package com.cboyxd.gitlab.ui.viewmodel

import android.net.Uri
import com.cboyxd.gitlab.IncomingLinks
import com.cboyxd.gitlab.data.api.normalizeBaseUrl
import com.cboyxd.gitlab.data.model.GitLabGroup
import com.cboyxd.gitlab.data.model.GitLabNamespace
import com.cboyxd.gitlab.data.model.GitLabProject
import com.cboyxd.gitlab.data.model.GitLabUser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Groups, people, global and project search, and routing GitLab web links to in-app screens. */
class ExploreFeature internal constructor(private val host: SessionHost) {

  private var groupJob: Job? = null
  private var userJob: Job? = null
  private var searchJob: Job? = null
  private var linkJob: Job? = null
  private val projectIdsByPath = mutableMapOf<String, Long>()
  private val namespacesByPath = mutableMapOf<String, GitLabNamespace>()

  internal fun reset() {
    listOf(groupJob, userJob, searchJob, linkJob).forEach { it?.cancel() }
    projectIdsByPath.clear()
    namespacesByPath.clear()
  }

  // -------------------------------------------------------------------------------------------
  // Groups
  // -------------------------------------------------------------------------------------------

  internal fun loadGroup(group: GitLabGroup) {
    groupJob?.cancel()
    host.state.update {
      it.copy(group = GroupDetailState(
        group = group,
        details = Loadable<GitLabGroup?>(group, loaded = true).start(),
        subgroups = emptyFeed<GitLabGroup>().start(),
        projects = emptyFeed<GitLabProject>().start(),
      ))
    }
    val path = group.fullPath
    val repo = host.repository
    groupJob = host.session.launch {
      launch { val r = host.api { repo.getGroup(it, path) }; apply(path) { g -> g.copy(details = g.details.settle(r.map { it as GitLabGroup? })) } }
      launch { val r = host.api { repo.getSubgroups(it, path) }; apply(path) { g -> g.copy(subgroups = g.subgroups.settle(r)) } }
      launch { val r = host.api { repo.getGroupProjects(it, path) }; apply(path) { g -> g.copy(projects = g.projects.settle(r)) } }
    }
  }

  fun retryGroup() {
    host.state.value.group.group?.let(::loadGroup)
  }

  private fun apply(path: String, transform: (GroupDetailState) -> GroupDetailState) {
    host.state.update { s -> if (s.group.group?.fullPath == path) s.copy(group = transform(s.group)) else s }
  }

  // -------------------------------------------------------------------------------------------
  // People
  // -------------------------------------------------------------------------------------------

  fun openUser(user: GitLabUser) {
    if (user.id > 0) host.navigateTo(ScreenDestination.UserDetail(user.id, user.username))
    else if (user.username.isNotBlank()) host.navigateTo(ScreenDestination.UserDetail(0, user.username))
  }

  fun openUsername(username: String) {
    if (username.isNotBlank()) host.navigateTo(ScreenDestination.UserDetail(0, username))
  }

  internal fun loadUser(userId: Long, username: String) {
    userJob?.cancel()
    val known = host.state.value.let { s ->
      listOfNotNull(s.currentUser.data).plus(s.search.users.data).firstOrNull { (userId > 0 && it.id == userId) || (username.isNotBlank() && it.username == username) }
    }
    host.state.update {
      it.copy(userDetail = UserDetailState(userId, username, Loadable<GitLabUser?>(known, loaded = known != null).start(), emptyFeed<GitLabProject>().start(), emptyFeed<com.cboyxd.gitlab.data.model.GitLabEvent>().start()))
    }
    val repo = host.repository
    userJob = host.session.launch {
      val byName: suspend () -> Result<GitLabUser> = {
        host.api { repo.findUser(it, username) }.mapCatching { it ?: throw IllegalStateException("No user named @$username on this server.") }
      }
      // GitLab.com hides /users/:id from anonymous requests but still answers the username lookup.
      val resolved = if (userId > 0) {
        host.api { repo.getUser(it, userId) }.let { r -> if (r.isFailure && username.isNotBlank()) byName() else r }
      } else byName()
      host.state.update { s -> if (!s.userDetail.matches(userId, username)) s else s.copy(userDetail = s.userDetail.copy(user = s.userDetail.user.settle(resolved.map { it as GitLabUser? }))) }
      val id = resolved.getOrNull()?.id ?: run {
        host.state.update { s -> if (!s.userDetail.matches(userId, username)) s else s.copy(userDetail = s.userDetail.copy(projects = s.userDetail.projects.success(emptyList()), events = s.userDetail.events.success(emptyList()))) }
        return@launch
      }
      launch {
        val r = host.api { repo.getUserProjects(it, id) }
        host.state.update { s -> if (!s.userDetail.matches(userId, username)) s else s.copy(userDetail = s.userDetail.copy(projects = s.userDetail.projects.settle(r))) }
      }
      launch {
        val r = host.api { repo.getUserEvents(it, id) }
        host.state.update { s -> if (!s.userDetail.matches(userId, username)) s else s.copy(userDetail = s.userDetail.copy(events = s.userDetail.events.settle(r))) }
      }
    }
  }

  fun retryUser() {
    val d = host.state.value.userDetail
    loadUser(d.userId, d.username)
  }

  private fun UserDetailState.matches(id: Long, name: String) = userId == id && username == name

  // -------------------------------------------------------------------------------------------
  // Search
  // -------------------------------------------------------------------------------------------

  fun openSearch(projectId: Long? = null, projectName: String? = null) {
    prepareSearch(projectId, projectName)
    host.navigateTo(ScreenDestination.Search(projectId, projectName))
  }

  internal fun prepareSearch(projectId: Long? = null, projectName: String? = null) {
    searchJob?.cancel()
    val current = host.state.value.search
    val defaultScope = if (projectId != null) SearchScope.ISSUES else SearchScope.PROJECTS
    host.state.update {
      if (current.projectId == projectId && projectId != null) {
        it.copy(search = current.copy(projectName = projectName ?: current.projectName))
      } else {
        it.copy(search = SearchState(scope = defaultScope, projectId = projectId, projectName = projectName))
      }
    }
  }

  fun setQuery(query: String) {
    host.state.update { it.copy(search = it.search.copy(query = query)) }
    scheduleSearch(debounce = true)
  }

  fun setScope(scope: SearchScope) {
    host.state.update { it.copy(search = it.search.copy(scope = scope)) }
    scheduleSearch(debounce = false)
  }

  private fun scheduleSearch(debounce: Boolean) {
    searchJob?.cancel()
    val search = host.state.value.search
    val query = search.query.trim()
    val projectId = search.projectId
    if (query.length < 2) {
      host.state.update {
        it.copy(
          search = SearchState(
            query = it.search.query,
            scope = it.search.scope,
            projectId = it.search.projectId,
            projectName = it.search.projectName,
          )
        )
      }
      return
    }
    val scope = if (projectId != null && search.scope == SearchScope.PROJECTS) SearchScope.ISSUES else search.scope
    val repo = host.repository
    searchJob = host.session.launch {
      if (debounce) delay(350)
      host.state.update { s -> s.copy(search = s.search.startScope(scope)) }
      when (scope) {
        SearchScope.PROJECTS -> host.api { repo.getProjects(it, query) }.let { r -> finish(query, projectId) { it.copy(projects = it.projects.settle(r)) } }
        SearchScope.ISSUES -> host.api { repo.searchIssues(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(issues = it.issues.settle(r)) } }
        SearchScope.MERGE_REQUESTS -> host.api { repo.searchMergeRequests(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(mergeRequests = it.mergeRequests.settle(r)) } }
        SearchScope.BLOBS -> host.api { repo.searchBlobs(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(blobs = it.blobs.settle(r)) } }
        SearchScope.COMMITS -> host.api { repo.searchCommits(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(commits = it.commits.settle(r)) } }
        SearchScope.NOTES -> host.api { repo.searchNotes(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(notes = it.notes.settle(r)) } }
        SearchScope.USERS -> host.api { repo.searchUsers(it, query, projectId) }.let { r -> finish(query, projectId) { it.copy(users = it.users.settle(r)) } }
      }
    }
  }

  private fun SearchState.startScope(scope: SearchScope) = when (scope) {
    SearchScope.PROJECTS -> copy(projects = projects.start())
    SearchScope.ISSUES -> copy(issues = issues.start())
    SearchScope.MERGE_REQUESTS -> copy(mergeRequests = mergeRequests.start())
    SearchScope.BLOBS -> copy(blobs = blobs.start())
    SearchScope.COMMITS -> copy(commits = commits.start())
    SearchScope.NOTES -> copy(notes = notes.start())
    SearchScope.USERS -> copy(users = users.start())
  }

  private fun finish(query: String, projectId: Long?, transform: (SearchState) -> SearchState) {
    host.state.update { s ->
      if (s.search.query.trim() != query || s.search.projectId != projectId) s
      else s.copy(search = transform(s.search))
    }
  }

  // -------------------------------------------------------------------------------------------
  // Links
  // -------------------------------------------------------------------------------------------

  /**
   * Opens a GitLab web URL inside the app when it points at the active server and at something
   * the app has a screen for. Returns false to let the caller open it in the browser.
   */
  fun handleLink(url: String): Boolean {
    val instance = host.state.value.activeInstance ?: return false
    val base = runCatching { Uri.parse(normalizeBaseUrl(instance.url)) }.getOrNull() ?: return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (!IncomingLinks.sameServer(uri.host, base.host)) return false
    val prefix = base.path.orEmpty().trimEnd('/')
    val rawPath = uri.path.orEmpty()
    val path = (if (prefix.isNotEmpty() && rawPath.startsWith(prefix)) rawPath.removePrefix(prefix) else rawPath).trim('/')
    if (path.isEmpty()) return false
    val (projectPath, rest) = when {
      path.startsWith("-/") -> "" to path.removePrefix("-/").split('/').filter { it.isNotEmpty() }
      "/-/" in path -> {
        val parts = path.split("/-/", limit = 2)
        parts[0] to parts[1].split('/').filter { it.isNotEmpty() }
      }
      else -> path to emptyList()
    }

    if (rest.isEmpty()) {
      return when {
        projectPath.startsWith("groups/") -> {
          val groupPath = Uri.decode(projectPath.removePrefix("groups/")).trim('/')
          if (groupPath.isEmpty()) false else resolveNamespace(url, groupPath, preferGroup = true)
        }
        projectPath.startsWith("users/") -> {
          val username = Uri.decode(projectPath.removePrefix("users/").substringBefore('/')).trim()
          if (username.isEmpty()) false else { openUsername(username); true }
        }
        '/' !in projectPath -> resolveNamespace(url, Uri.decode(projectPath))
        else -> withProjectOrGroup(url, projectPath)
      }
    }

    val kind = rest[0]
    val arg = rest.getOrNull(1)
    if (projectPath.isEmpty()) {
      return when (kind) {
        "snippets" -> arg?.toLongOrNull()?.let { sid ->
          host.navigateTo(ScreenDestination.FileViewer(0L, "snippet-$sid", snippetId = sid))
          true
        } ?: false
        else -> false
      }
    }
    if (projectPath.startsWith("groups/") || projectPath.startsWith("users/")) return false

    return when (kind) {
      "issues" -> if (arg == null) {
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectSectionList(it, ProjectSection.ISSUES)) }
      } else {
        arg.toLongOrNull()?.let { iid -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.IssueDetail(it, iid)) } } ?: false
      }
      "merge_requests" -> if (arg == null) {
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectSectionList(it, ProjectSection.MERGE_REQUESTS)) }
      } else {
        arg.toLongOrNull()?.let { iid -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.MergeRequestDetail(it, iid)) } } ?: false
      }
      "pipelines" -> if (arg == null) {
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectSectionList(it, ProjectSection.PIPELINES)) }
      } else {
        arg.toLongOrNull()?.let { pid -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.PipelineDetail(it, pid)) } } ?: false
      }
      "jobs" -> arg?.toLongOrNull()?.let { jid -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.JobDetail(it, jid)) } } ?: false
      "commit" -> arg?.let { sha -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.CommitDetail(it, sha)) } } ?: false
      "commits" -> withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectSectionList(it, ProjectSection.COMMITS)) }
      "releases" -> if (arg == null) {
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectSectionList(it, ProjectSection.RELEASES)) }
      } else {
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ReleaseDetail(it, Uri.decode(arg))) }
      }
      "tree" -> {
        val ref = arg?.takeIf { it.isNotBlank() }?.let(Uri::decode) ?: return false
        val treePath = rest.drop(2).joinToString("/") { Uri.decode(it) }.trim('/')
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.ProjectCode(it, ref = ref, path = treePath)) }
      }
      "blob" -> {
        // /-/blob/<ref>/<path>: refs with slashes are ambiguous; assume a single-segment ref.
        val ref = arg?.takeIf { it.isNotBlank() }?.let(Uri::decode) ?: return false
        val file = rest.drop(2).joinToString("/") { Uri.decode(it) }.takeIf { it.isNotEmpty() } ?: return false
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.FileViewer(it, file, ref)) }
      }
      "snippets" -> arg?.toLongOrNull()?.let { sid ->
        withProject(url, projectPath) { host.navigateTo(ScreenDestination.FileViewer(it, "snippet-$sid", snippetId = sid)) }
      } ?: false
      else -> false
    }
  }

  /** Resolves a top-level or explicit group/user path via `/namespaces/:path`, falling back to user lookup or browser. */
  private fun resolveNamespace(url: String, slug: String, preferGroup: Boolean = false): Boolean {
    val key = slug.lowercase()
    namespacesByPath[key]?.let { ns ->
      routeNamespace(ns, slug)
      return true
    }
    val current = host.state.value
    (current.groups.data + current.group.subgroups.data + listOfNotNull(current.group.group))
      .firstOrNull { it.fullPath.equals(slug, ignoreCase = true) }
      ?.let { group ->
        host.navigateTo(ScreenDestination.GroupProjects(group))
        return true
      }
    if (!preferGroup) {
      (listOfNotNull(current.currentUser.data) + current.search.users.data)
        .firstOrNull { it.username.equals(slug, ignoreCase = true) }
        ?.let { user ->
          openUser(user)
          return true
        }
    }
    linkJob?.cancel()
    linkJob = host.session.launch {
      val nsResult = host.api { host.repository.getNamespace(it, slug) }
      val ns = nsResult.getOrNull()
      if (ns != null) {
        namespacesByPath[key] = ns
        routeNamespace(ns, slug)
      } else if (preferGroup) {
        host.api { host.repository.getGroup(it, slug) }.fold(
          onSuccess = { group -> host.navigateTo(ScreenDestination.GroupProjects(group)) },
          onFailure = { host.openExternal(url) },
        )
      } else {
        val user = host.api { host.repository.findUser(it, slug) }.getOrNull()
        if (user != null) openUser(user) else host.openExternal(url)
      }
    }
    return true
  }

  private fun routeNamespace(ns: GitLabNamespace, fallbackSlug: String) {
    val full = ns.fullPath.ifBlank { fallbackSlug }
    if (ns.kind.equals("group", ignoreCase = true)) {
      host.navigateTo(
        ScreenDestination.GroupProjects(
          GitLabGroup(
            id = ns.id,
            name = ns.name.ifBlank { full.substringAfterLast('/') },
            fullPath = full,
            webUrl = ns.webUrl.orEmpty(),
            avatarUrl = ns.avatarUrl,
          )
        )
      )
    } else {
      openUsername(ns.path.ifBlank { fallbackSlug })
    }
  }

  private fun withProjectOrGroup(url: String, path: String): Boolean {
    val key = path.lowercase()
    projectIdsByPath[key]?.let { host.navigateTo(ScreenDestination.ProjectDetail(it)); return true }
    val current = host.state.value
    (current.projects.data + current.search.projects.data + listOfNotNull(current.project.project.data))
      .firstOrNull { it.pathWithNamespace.equals(path, ignoreCase = true) }
      ?.let { projectIdsByPath[key] = it.id; host.navigateTo(ScreenDestination.ProjectDetail(it.id)); return true }
    linkJob?.cancel()
    linkJob = host.session.launch {
      val project = host.api { host.repository.getProjectByPath(it, path) }.getOrNull()
      if (project != null) {
        projectIdsByPath[key] = project.id
        host.navigateTo(ScreenDestination.ProjectDetail(project.id))
      } else {
        val ns = host.api { host.repository.getNamespace(it, path) }.getOrNull()
        if (ns != null && ns.kind.equals("group", ignoreCase = true)) {
          namespacesByPath[key] = ns
          routeNamespace(ns, path)
        } else {
          host.openExternal(url)
        }
      }
    }
    return true
  }

  /** Resolves a project path to its id (cached), then runs [then]; falls back to the browser. */
  private fun withProject(url: String, path: String, then: (Long) -> Unit): Boolean {
    val key = path.lowercase()
    projectIdsByPath[key]?.let { then(it); return true }
    val current = host.state.value
    (current.projects.data + current.search.projects.data + listOfNotNull(current.project.project.data))
      .firstOrNull { it.pathWithNamespace.equals(path, ignoreCase = true) }
      ?.let { projectIdsByPath[key] = it.id; then(it.id); return true }
    linkJob?.cancel()
    linkJob = host.session.launch {
      host.api { host.repository.getProjectByPath(it, path) }.fold(
        onSuccess = { project -> projectIdsByPath[key] = project.id; then(project.id) },
        onFailure = { host.openExternal(url) },
      )
    }
    return true
  }
}
