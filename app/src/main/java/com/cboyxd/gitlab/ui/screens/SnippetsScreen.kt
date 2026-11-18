package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.data.model.GitLabSnippet
import com.cboyxd.gitlab.ui.components.EmptyState
import com.cboyxd.gitlab.ui.components.ErrorState
import com.cboyxd.gitlab.ui.components.FullScreenState
import com.cboyxd.gitlab.ui.components.GlBadge
import com.cboyxd.gitlab.ui.components.GlDivider
import com.cboyxd.gitlab.ui.components.GlIconAction
import com.cboyxd.gitlab.ui.components.GlRow
import com.cboyxd.gitlab.ui.components.GlTopBar
import com.cboyxd.gitlab.ui.components.InlineError
import com.cboyxd.gitlab.ui.components.LazyCodeView
import com.cboyxd.gitlab.ui.components.LoadingState
import com.cboyxd.gitlab.ui.components.MonoPill
import com.cboyxd.gitlab.ui.components.Tone
import com.cboyxd.gitlab.ui.components.shareText
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace
import com.cboyxd.gitlab.ui.viewmodel.SnippetDetailState
import com.cboyxd.gitlab.ui.viewmodel.SnippetsState

@Composable
fun SnippetsScreen(
  state: SnippetsState,
  projectTitle: String?,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onSelectSnippet: (GitLabSnippet) -> Unit,
  modifier: Modifier = Modifier,
) {
  val feed = state.items
  val isProject = state.projectId != null
  Column(modifier = modifier.fillMaxSize().background(Gl.colors.bg)) {
    GlTopBar(
      title = if (isProject) "Project snippets" else "Snippets",
      overline = projectTitle?.takeIf { isProject && it.isNotBlank() },
      onBack = onBack,
    )
    GlDivider()
    val error = feed.error
    when {
      error != null && feed.data.isEmpty() -> FullScreenState { ErrorState(message = error, onRetry = onRetry) }
      feed.initialLoading || (!feed.loaded && error == null) -> FullScreenState { LoadingState(label = "Loading snippets") }
      feed.data.isEmpty() -> FullScreenState {
        EmptyState(
          icon = Icons.Outlined.Code,
          title = "No snippets",
          body = if (isProject) "Snippets created in this project appear here." else "Your personal snippets appear here.",
        )
      }
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("snippets_list"),
        contentPadding = PaddingValues(bottom = GlSpace.group),
      ) {
        if (error != null) {
          item(key = "error") {
            InlineError(message = error, onRetry = onRetry, modifier = Modifier.padding(horizontal = GlSpace.margin, vertical = 4.dp))
          }
        }
        items(feed.data, key = { it.id }) { snippet ->
          SnippetRow(snippet = snippet, onClick = { onSelectSnippet(snippet) })
          GlDivider()
        }
        item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
      }
    }
  }
}

@Composable
private fun SnippetRow(snippet: GitLabSnippet, onClick: () -> Unit) {
  val title = snippet.title.ifBlank { snippet.fileName.ifBlank { "Snippet #${snippet.id}" } }
  val subtitleParts = buildList {
    snippet.fileName.takeIf { it.isNotBlank() && it != title }?.let(::add)
    if (snippet.files.size > 1) add("${snippet.files.size} files")
    snippet.updatedAt.ifBlank { snippet.createdAt }.takeIf { it.isNotBlank() }?.let(::add)
  }
  GlRow(
    title = title,
    subtitle = subtitleParts.joinToString(" · ").takeIf { it.isNotBlank() },
    leading = {
      Icon(Icons.Outlined.Description, contentDescription = null, tint = Gl.colors.textSecondary, modifier = Modifier.size(22.dp))
    },
    trailing = {
      VisibilityBadge(snippet.visibility)
    },
    onClick = onClick,
    onClickLabel = "Open snippet $title",
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SnippetDetailScreen(
  state: SnippetDetailState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onOpenInBrowser: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val c = Gl.colors
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val snippet = state.snippet.data
  val raw = state.rawContent.data
  var wrap by rememberSaveable { mutableStateOf(true) }
  var menuOpen by remember { mutableStateOf(false) }

  val title = snippet?.title?.ifBlank { snippet.fileName } ?: "Snippet #${state.snippetId}"
  val primaryFile = snippet?.fileName?.ifBlank { snippet.files.firstOrNull()?.path.orEmpty() }.orEmpty()
  val webUrl = snippet?.webUrl?.takeIf { it.isNotBlank() }

  Column(modifier = modifier.fillMaxSize().background(c.bg)) {
    GlTopBar(
      title = title,
      subtitle = primaryFile.takeIf { it.isNotBlank() && it != title },
      onBack = onBack,
      actions = {
        if (webUrl != null) {
          GlIconAction(Icons.Outlined.Share, "Share", onClick = { shareText(context, webUrl, title = title) })
        }
        if (raw != null || webUrl != null) {
          Box {
            GlIconAction(Icons.Outlined.MoreVert, "More options", onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surface2) {
              val colors = MenuDefaults.itemColors(textColor = c.textPrimary, leadingIconColor = c.textSecondary)
              if (!raw.isNullOrEmpty()) {
                DropdownMenuItem(
                  text = { Text(if (wrap) "Disable line wrap" else "Wrap lines", style = Gl.type.body) },
                  leadingIcon = { Icon(Icons.AutoMirrored.Outlined.WrapText, contentDescription = null) },
                  colors = colors,
                  onClick = { menuOpen = false; wrap = !wrap },
                )
                DropdownMenuItem(
                  text = { Text("Copy contents", style = Gl.type.body) },
                  leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                  colors = colors,
                  onClick = { menuOpen = false; clipboard.setText(AnnotatedString(raw)) },
                )
              }
              if (webUrl != null) {
                DropdownMenuItem(
                  text = { Text("Open in browser", style = Gl.type.body) },
                  leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                  colors = colors,
                  onClick = { menuOpen = false; onOpenInBrowser(webUrl) },
                )
              }
            }
          }
        }
      },
    )
    GlDivider()

    if (snippet != null) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(c.surface)
          .padding(horizontal = GlSpace.margin, vertical = 12.dp),
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          VisibilityBadge(snippet.visibility)
          if (primaryFile.isNotBlank()) MonoPill(primaryFile)
          val authorName = snippet.author.name.ifBlank { snippet.author.username }
          val time = snippet.updatedAt.ifBlank { snippet.createdAt }
          val meta = listOfNotNull(authorName.takeIf { it.isNotBlank() }, time.takeIf { it.isNotBlank() }).joinToString(" · ")
          if (meta.isNotBlank()) {
            Text(
              text = meta,
              style = Gl.type.meta,
              color = c.textSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.align(Alignment.CenterVertically),
            )
          }
        }
        val desc = snippet.description?.takeIf { it.isNotBlank() }
        if (desc != null) {
          Spacer(Modifier.height(8.dp))
          Text(desc, style = Gl.type.bodySmall, color = c.textSecondary)
        }
        if (snippet.files.size > 1) {
          Spacer(Modifier.height(8.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            snippet.files.forEach { file ->
              MonoPill(file.path.ifBlank { "file" })
            }
          }
        }
      }
      GlDivider()
    }

    val rawError = state.rawContent.error
    val metaError = state.snippet.error
    when {
      state.rawContent.loading && raw == null -> FullScreenState { LoadingState(label = "Loading snippet") }
      rawError != null && raw == null -> FullScreenState { ErrorState(message = rawError, onRetry = onRetry) }
      metaError != null && snippet == null && raw == null -> FullScreenState { ErrorState(message = metaError, onRetry = onRetry) }
      raw == null -> FullScreenState { LoadingState(label = "Loading snippet") }
      raw.isEmpty() -> FullScreenState { EmptyState(Icons.Outlined.Code, "Empty snippet", "This snippet has no content.") }
      else -> LazyCodeView(
        code = raw,
        path = primaryFile,
        wrap = wrap,
        modifier = Modifier.weight(1f).testTag("snippet_code_view"),
      )
    }
  }
}

@Composable
private fun VisibilityBadge(visibility: String) {
  val (label, tone) = when (visibility.lowercase()) {
    "public" -> "Public" to Tone.Neutral
    "internal" -> "Internal" to Tone.Info
    "private" -> "Private" to Tone.Warning
    else -> visibility.replaceFirstChar { it.uppercase() }.ifBlank { "Private" } to Tone.Neutral
  }
  GlBadge(text = label, tone = tone)
}
