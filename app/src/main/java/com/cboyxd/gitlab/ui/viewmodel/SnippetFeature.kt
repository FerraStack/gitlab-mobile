package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.model.GitLabSnippet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Personal and project snippets list + raw snippet content viewer. */
class SnippetFeature internal constructor(private val host: SessionHost) {
  private var listJob: Job? = null
  private var metaJob: Job? = null
  private var rawJob: Job? = null

  fun openList(projectId: Long? = null) {
    host.navigateTo(ScreenDestination.SnippetsList(projectId))
  }

  fun openDetail(snippet: GitLabSnippet) {
    host.state.update {
      it.copy(
        snippetDetail = SnippetDetailState(
          snippetId = snippet.id,
          projectId = snippet.projectId,
          snippet = Loadable(snippet, loaded = true),
          rawContent = Loadable(null, loading = true),
        )
      )
    }
    host.navigateTo(ScreenDestination.SnippetDetail(snippet.id, snippet.projectId))
  }

  fun openDetail(snippetId: Long, projectId: Long? = null) {
    host.navigateTo(ScreenDestination.SnippetDetail(snippetId, projectId))
  }

  fun loadList(projectId: Long? = null, force: Boolean = false) {
    val current = host.state.value.snippets
    val sameScope = current.projectId == projectId
    if (!force && sameScope && current.items.loaded && current.items.error == null) return
    listJob?.cancel()
    host.state.update {
      val base = if (sameScope) it.snippets else SnippetsState(projectId = projectId)
      it.copy(snippets = base.copy(projectId = projectId, items = base.items.start()))
    }
    listJob = host.session.launch {
      val result = host.api { instance ->
        if (projectId != null) host.repository.getProjectSnippets(instance, projectId)
        else host.repository.getSnippets(instance)
      }
      host.state.update {
        it.copy(snippets = it.snippets.copy(items = it.snippets.items.settle(result)))
      }
    }
  }

  fun retryList() {
    loadList(host.state.value.snippets.projectId, force = true)
  }

  fun loadDetail(snippetId: Long, projectId: Long? = null, force: Boolean = false) {
    val current = host.state.value.snippetDetail
    val same = current.snippetId == snippetId && current.projectId == projectId
    if (!force && same && current.snippet.loaded && current.rawContent.loaded && current.rawContent.error == null) return

    metaJob?.cancel()
    rawJob?.cancel()

    val existingMeta = current.snippet.data?.takeIf { same && it.id == snippetId }
    host.state.update {
      it.copy(
        snippetDetail = SnippetDetailState(
          snippetId = snippetId,
          projectId = projectId,
          snippet = if (existingMeta != null) Loadable(existingMeta, loaded = true) else Loadable(null, loading = true),
          rawContent = Loadable(if (same) current.rawContent.data else null, loading = true),
        )
      )
    }

    if (existingMeta == null) {
      metaJob = host.session.launch {
        val result = host.api { host.repository.getSnippet(it, snippetId, projectId) }
        host.state.update {
          if (it.snippetDetail.snippetId != snippetId || it.snippetDetail.projectId != projectId) it
          else it.copy(snippetDetail = it.snippetDetail.copy(snippet = it.snippetDetail.snippet.settle(result)))
        }
      }
    }

    rawJob = host.session.launch {
      val result = host.api { host.repository.getSnippetRaw(it, snippetId, projectId) }
      host.state.update {
        if (it.snippetDetail.snippetId != snippetId || it.snippetDetail.projectId != projectId) it
        else it.copy(snippetDetail = it.snippetDetail.copy(rawContent = it.snippetDetail.rawContent.settle(result)))
      }
    }
  }

  fun retryDetail() {
    val detail = host.state.value.snippetDetail
    loadDetail(detail.snippetId, detail.projectId, force = true)
  }

  fun reset() {
    listJob?.cancel()
    metaJob?.cancel()
    rawJob?.cancel()
  }
}
