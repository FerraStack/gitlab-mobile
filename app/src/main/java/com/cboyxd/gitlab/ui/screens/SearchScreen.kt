package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.data.model.GitLabBlobSearchResult
import com.cboyxd.gitlab.data.model.GitLabCommit
import com.cboyxd.gitlab.data.model.GitLabIssue
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabNoteSearchResult
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.ui.components.Avatar
import com.cboyxd.gitlab.ui.components.EmptyState
import com.cboyxd.gitlab.ui.components.ErrorState
import com.cboyxd.gitlab.ui.components.GlDivider
import com.cboyxd.gitlab.ui.components.GlFilterRow
import com.cboyxd.gitlab.ui.components.InlineError
import com.cboyxd.gitlab.ui.components.IssueRow
import com.cboyxd.gitlab.ui.components.ListsSearchField
import com.cboyxd.gitlab.ui.components.LoadingState
import com.cboyxd.gitlab.ui.components.MergeRequestRow
import com.cboyxd.gitlab.ui.components.UserRow
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.SearchScope
import com.cboyxd.gitlab.ui.viewmodel.SearchState

@Composable
fun SearchScreen(
  state: SearchState,
  isSignedIn: Boolean,
  onBack: () -> Unit,
  onQueryChange: (String) -> Unit,
  onScopeChange: (SearchScope) -> Unit,
  onOpenProject: (Long) -> Unit,
  onOpenIssue: (GitLabIssue) -> Unit,
  onOpenMergeRequest: (GitLabMergeRequest) -> Unit,
  onOpenUser: (GitLabUser) -> Unit,
  modifier: Modifier = Modifier,
  onOpenBlob: (GitLabBlobSearchResult) -> Unit = {},
  onOpenCommit: (GitLabCommit) -> Unit = {},
  onOpenNote: (GitLabNoteSearchResult) -> Unit = {},
) {
  val c = Gl.colors
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

  val isProjectSearch = state.projectId != null
  val scopes = remember(isProjectSearch) {
    if (isProjectSearch) SearchScope.entries.filter { it != SearchScope.PROJECTS }
    else SearchScope.entries
  }
  val effectiveScope = if (isProjectSearch && state.scope == SearchScope.PROJECTS) SearchScope.ISSUES else state.scope
  val searchTitle = when {
    state.projectName != null -> "Search in ${state.projectName}"
    isProjectSearch -> "Search project"
    else -> "Search GitLab"
  }

  Column(modifier = modifier.fillMaxSize().background(c.bg)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = GlSpace.margin, top = 8.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.textPrimary)
      }
      ListsSearchField(
        query = state.query,
        onChange = onQueryChange,
        placeholder = searchTitle,
        modifier = Modifier
          .weight(1f)
          .focusRequester(focus),
      )
    }
    GlFilterRow(
      options = scopes,
      selected = effectiveScope,
      label = { it.title },
      onSelect = onScopeChange,
    )
    GlDivider(modifier = Modifier.padding(top = 2.dp))

    val query = state.query.trim()
    val retry = { onScopeChange(effectiveScope) }
    when {
      query.length < 2 -> EmptyState(
        icon = Icons.Outlined.Search,
        title = searchTitle,
        body = if (isProjectSearch) {
          "Find issues, merge requests, code, commits and comments."
        } else {
          "Find projects, issues, merge requests, code, commits and people."
        },
      )
      !isSignedIn && !isProjectSearch && effectiveScope != SearchScope.PROJECTS && effectiveScope != SearchScope.USERS -> EmptyState(
        icon = Icons.Outlined.Lock,
        title = "Sign in to search",
        body = "Global ${effectiveScope.title.lowercase()} search requires an account on this server.",
      )
      else -> when (effectiveScope) {
        SearchScope.PROJECTS -> Results(state.projects, query, retry, key = { it.id }) { project ->
          ListsProjectRow(project, onClick = { onOpenProject(project.id) })
          GlDivider()
        }
        SearchScope.ISSUES -> Results(state.issues, query, retry, key = { "${it.projectId}-${it.iid}" }) { issue ->
          IssueRow(issue = issue, onClick = { onOpenIssue(issue) })
          GlDivider()
        }
        SearchScope.MERGE_REQUESTS -> Results(state.mergeRequests, query, retry, key = { "${it.projectId}-${it.iid}" }) { mr ->
          MergeRequestRow(mr = mr, onClick = { onOpenMergeRequest(mr) })
          GlDivider()
        }
        SearchScope.BLOBS -> Results(
          state.blobs,
          query,
          retry,
          key = { "${it.projectId}:${it.ref}:${it.path}:${it.startline}:${it.filename}" },
        ) { blob ->
          BlobSearchRow(blob = blob, onClick = { onOpenBlob(blob) })
          GlDivider()
        }
        SearchScope.COMMITS -> Results(state.commits, query, retry, key = { "${it.projectId ?: 0}:${it.id}" }) { commit ->
          DetailCommitRow(commit = commit, onClick = { onOpenCommit(commit) })
          GlDivider()
        }
        SearchScope.NOTES -> Results(state.notes, query, retry, key = { "${it.projectId}:${it.id}" }) { note ->
          val openable = note.projectId > 0 && note.noteableIid != null &&
            (note.noteableType == "Issue" || note.noteableType == "MergeRequest")
          NoteSearchRow(note = note, onClick = if (openable) ({ onOpenNote(note) }) else null)
          GlDivider()
        }
        SearchScope.USERS -> Results(state.users, query, retry, key = { it.id }) { user ->
          UserRow(user = user, onClick = { onOpenUser(user) })
          GlDivider(inset = 64.dp)
        }
      }
    }
  }
}

@Composable
private fun BlobSearchRow(
  blob: GitLabBlobSearchResult,
  onClick: () -> Unit,
) {
  val c = Gl.colors
  val title = blob.filename.ifBlank { blob.path.substringAfterLast('/') }.ifBlank { blob.path }
  val subtitle = listOfNotNull(
    blob.path.takeIf { it.isNotBlank() },
    blob.ref.takeIf { it.isNotBlank() },
    if (blob.startline > 0) "line ${blob.startline}" else null,
  ).joinToString(" · ")
  val snippet = blob.data.trim().take(240)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClickLabel = "Open file", onClick = onClick)
      .padding(horizontal = GlSpace.margin, vertical = 12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(
      Icons.Outlined.Code,
      contentDescription = null,
      tint = c.accent,
      modifier = Modifier.padding(top = 2.dp).size(20.dp),
    )
    Spacer(Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = Gl.type.rowTitle.copy(fontWeight = FontWeight.Medium),
        color = c.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          subtitle,
          style = Gl.type.bodySmall,
          color = c.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (snippet.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = snippet,
          style = Gl.type.monoSmall,
          color = c.textPrimary,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.surface2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun NoteSearchRow(
  note: GitLabNoteSearchResult,
  onClick: (() -> Unit)?,
) {
  val c = Gl.colors
  val author = note.author.name.ifBlank { note.author.username }.ifBlank { "Someone" }
  val targetLabel = when (note.noteableType) {
    "Issue" -> note.noteableIid?.let { "Issue #$it" } ?: "Issue"
    "MergeRequest" -> note.noteableIid?.let { "Merge request !$it" } ?: "Merge request"
    else -> note.noteableType ?: "Comment"
  }
  val header = listOfNotNull(targetLabel, note.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open thread", onClick = onClick) else Modifier)
      .padding(horizontal = GlSpace.margin, vertical = 12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(
      Icons.Outlined.ChatBubbleOutline,
      contentDescription = null,
      tint = c.textSecondary,
      modifier = Modifier.padding(top = 2.dp).size(20.dp),
    )
    Spacer(Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          header,
          style = Gl.type.bodySmall.copy(fontWeight = FontWeight.Medium),
          color = c.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (note.createdAt.isNotBlank()) {
          Spacer(Modifier.width(8.dp))
          Text(shortAge(note.createdAt), style = Gl.type.bodySmall, color = c.textTertiary, maxLines = 1)
        }
      }
      if (note.body.isNotBlank()) {
        Text(
          note.body.trim(),
          style = Gl.type.body,
          color = c.textPrimary,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(note.author.avatarUrl, author, 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
          author,
          style = Gl.type.bodySmall,
          color = c.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun <T> Results(
  feed: Loadable<List<T>>,
  query: String,
  onRetry: () -> Unit,
  key: (T) -> Any,
  row: @Composable (T) -> Unit,
) {
  when {
    feed.data.isEmpty() && (feed.loading || !feed.loaded) && feed.error == null -> LoadingState(label = "Searching")
    feed.error != null && feed.data.isEmpty() -> ErrorState(feed.error, onRetry)
    feed.data.isEmpty() -> EmptyState(
      icon = Icons.Outlined.SearchOff,
      title = "No results for “$query”",
      body = "Check the spelling or try a shorter search.",
    )
    else -> LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = GlSpace.group),
    ) {
      if (feed.error != null) {
        item(key = "error") {
          InlineError(feed.error, Modifier.padding(horizontal = GlSpace.margin, vertical = 4.dp), onRetry)
        }
      }
      items(feed.data, key = { key(it) }) { row(it) }
    }
  }
}
