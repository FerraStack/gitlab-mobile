package com.cboyxd.gitlab.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.data.model.DiffLineAnchor
import com.cboyxd.gitlab.data.model.GitLabDiscussion
import com.cboyxd.gitlab.data.model.GitLabFileDiff
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------------------------

enum class DiffKind { Context, Add, Remove, Hunk }

/**
 * One rendered diff line. [changed] marks the part of an edited line that differs from its
 * partner on the other side, so the edit stands out inside the line.
 */
@Immutable
data class DiffLine(
  val kind: DiffKind,
  val oldNo: Int?,
  val newNo: Int?,
  val text: String,
  val changed: IntRange? = null,
)

@Immutable
data class DiffStats(val added: Int, val removed: Int)

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

/** Parses a unified diff body (as GitLab returns it, without the ---/+++ file lines). */
fun parseUnifiedDiff(diff: String): List<DiffLine> {
  val out = ArrayList<DiffLine>()
  var oldNo = 0
  var newNo = 0
  var inHunk = false
  val raw = diff.replace("\t", "    ").lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
  for (line in raw) {
    when {
      line.startsWith("@@") -> {
        inHunk = true
        HUNK.matchEntire(line)?.let { m ->
          oldNo = m.groupValues[1].toInt()
          newNo = m.groupValues[2].toInt()
        }
        out += DiffLine(DiffKind.Hunk, null, null, line)
      }
      line.startsWith("\\") -> out += DiffLine(DiffKind.Hunk, null, null, line.removePrefix("\\ "))
      !inHunk -> Unit
      line.startsWith("+") -> out += DiffLine(DiffKind.Add, null, newNo++, line.drop(1))
      line.startsWith("-") -> out += DiffLine(DiffKind.Remove, oldNo++, null, line.drop(1))
      else -> out += DiffLine(DiffKind.Context, oldNo++, newNo++, line.removePrefix(" "))
    }
  }
  return markWordChanges(out)
}

/** Pairs each run of removed lines with the run of added lines after it and marks what changed. */
private fun markWordChanges(lines: List<DiffLine>): List<DiffLine> {
  val result = lines.toMutableList()
  var i = 0
  while (i < result.size) {
    if (result[i].kind != DiffKind.Remove) { i++; continue }
    val removeStart = i
    while (i < result.size && result[i].kind == DiffKind.Remove) i++
    val addStart = i
    while (i < result.size && result[i].kind == DiffKind.Add) i++
    val removed = addStart - removeStart
    val added = i - addStart
    if (removed == added && removed in 1..20) {
      for (k in 0 until removed) {
        val a = result[removeStart + k]
        val b = result[addStart + k]
        val (ra, rb) = changedRanges(a.text, b.text) ?: continue
        result[removeStart + k] = a.copy(changed = ra)
        result[addStart + k] = b.copy(changed = rb)
      }
    }
  }
  return result
}

private fun changedRanges(a: String, b: String): Pair<IntRange, IntRange>? {
  if (a == b) return null
  var prefix = 0
  val max = minOf(a.length, b.length)
  while (prefix < max && a[prefix] == b[prefix]) prefix++
  var suffix = 0
  while (suffix < max - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++
  val aEnd = a.length - suffix
  val bEnd = b.length - suffix
  // A line rewritten almost entirely reads better without an inner highlight.
  if (prefix == 0 && suffix == 0) return null
  return (prefix until aEnd) to (prefix until bEnd)
}

fun diffStats(diffs: List<GitLabFileDiff>): DiffStats {
  var added = 0
  var removed = 0
  diffs.forEach { d ->
    var inHunk = false
    d.diff.lineSequence().forEach { line ->
      when {
        line.startsWith("@@") -> inHunk = true
        inHunk && line.startsWith("+") -> added++
        inHunk && line.startsWith("-") -> removed++
      }
    }
  }
  return DiffStats(added, removed)
}

/** Highlight the old and new side of each hunk together, retaining multiline syntax state. */
fun prepareDiffLines(lines: List<DiffLine>, path: String, colors: GlColors): List<AnnotatedString> {
  val result = lines.map { AnnotatedString(it.text) }.toMutableList()
  if (lines.sumOf { it.text.length } < 60_000) {
    var start = 0
    while (start < lines.size) {
      if (lines[start].kind == DiffKind.Hunk) { start++; continue }
      var end = start
      while (end < lines.size && lines[end].kind != DiffKind.Hunk) end++
      for (oldSide in listOf(true, false)) {
        val indices = (start until end).filter {
          lines[it].kind != if (oldSide) DiffKind.Add else DiffKind.Remove
        }
        val highlighted = highlightForPath(indices.joinToString("\n") { lines[it].text }, path, colors).splitLines()
        indices.forEachIndexed { n, index -> result[index] = highlighted.getOrElse(n) { AnnotatedString(lines[index].text) } }
      }
      start = end
    }
  }
  return lines.mapIndexed { index, line ->
    val base = result[index]
    val range = line.changed
    if (range == null || range.isEmpty()) base
    else buildAnnotatedString {
      append(base)
      val word = if (line.kind == DiffKind.Add) colors.diffAddWordBg else colors.diffRemoveWordBg
      addStyle(SpanStyle(background = word), range.first.coerceIn(0, base.length), (range.last + 1).coerceIn(0, base.length))
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

/** Characters a diff line shows when scrolling sideways; as in the file viewer. */
const val MAX_DIFF_COLUMNS = 800
/** Characters a wrapped diff line shows; longer lines (minified code) are cut with "…". */
const val MAX_DIFF_WRAPPED_CHARS = 5_000

/** [text] cut to what a row can show, with "…" when something was left out. */
internal fun diffDisplayText(text: AnnotatedString, wrapped: Boolean): AnnotatedString {
  val limit = if (wrapped) MAX_DIFF_WRAPPED_CHARS else MAX_DIFF_COLUMNS
  return if (text.length > limit) text.subSequence(0, limit) + AnnotatedString("…") else text
}

/**
 * Width of an unwrapped diff row: wide enough for the longest shown line, never narrower than the
 * screen. Lines are capped at [MAX_DIFF_COLUMNS], so the width stays far below what Compose can
 * measure (about 262,000 px), which a 10,000-character minified line used to exceed.
 */
internal fun diffCodeWidth(longestLine: Int, fontSizeSp: Float, fontScale: Float, gutters: Dp, screenWidth: Dp): Dp {
  val columns = (longestLine.coerceAtMost(MAX_DIFF_COLUMNS) + 1).coerceAtLeast(1)
  return (columns * fontSizeSp * 0.65f * fontScale).dp.plus(gutters + 24.dp).coerceAtLeast(screenWidth)
}

/** Width of the line-number gutter for numbers up to [maxLine]. */
/** Where a comment on this line goes in [diff]; null for hunk headers. */
fun DiffLine.anchorIn(diff: GitLabFileDiff): DiffLineAnchor? = when (kind) {
  DiffKind.Hunk -> null
  DiffKind.Add -> DiffLineAnchor(diff.oldPath, diff.newPath, oldLine = null, newLine = newNo)
  DiffKind.Remove -> DiffLineAnchor(diff.oldPath, diff.newPath, oldLine = oldNo, newLine = null)
  DiffKind.Context -> DiffLineAnchor(diff.oldPath, diff.newPath, oldLine = oldNo, newLine = newNo)
}

/**
 * Line [newNo] of the new version exactly as the diff has it. The parsed text has tabs expanded
 * for display, which a suggestion must not copy into the code.
 */
fun rawNewLine(diff: String, newNo: Int): String? {
  var next = 0
  var inHunk = false
  for (line in diff.lineSequence()) {
    when {
      line.startsWith("@@") -> {
        inHunk = true
        next = HUNK.matchEntire(line)?.groupValues?.get(2)?.toInt() ?: next
      }
      !inHunk || line.isEmpty() || line.startsWith("\\") || line.startsWith("-") -> Unit
      else -> {
        if (next == newNo) return line.drop(1)
        next++
      }
    }
  }
  return null
}

/** Identifies a line across the diff and thread positions: the new side when it has one. */
fun diffLineKey(oldPath: String, newPath: String, oldLine: Int?, newLine: Int?): String? = when {
  newLine != null -> "new:$newPath:$newLine"
  oldLine != null -> "old:$oldPath:$oldLine"
  else -> null
}

/** Lines that have threads on [headSha], the version the page shows (older versions' line numbers differ). */
fun commentedLines(discussions: List<GitLabDiscussion>, headSha: String?): Set<String> =
  discussions.mapNotNullTo(HashSet()) { d ->
    d.notes.firstNotNullOfOrNull { it.position }
      ?.takeIf { headSha != null && it.headSha == headSha }
      ?.let { diffLineKey(it.oldPath, it.newPath, it.oldLine, it.newLine) }
  }

fun diffGutterWidth(maxLine: Int): Dp = (maxLine.toString().length.coerceAtLeast(2) * 8 + 16).dp

/**
 * Width of the diff line-number gutter measured for the current font size and system font scale,
 * so line numbers don't clip at large font sizes.
 */
@Composable
fun rememberDiffGutterWidth(maxLine: Int): Dp {
  val options = LocalDiffDisplayOptions.current
  val style = Gl.type.mono.copy(fontSize = (options.fontSize - 1).sp)
  val digits = maxLine.toString().length.coerceAtLeast(2)
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val textWidth = remember(style, digits, density.fontScale, density.density) {
    val sample = "8".repeat(digits)
    with(density) { measurer.measure(sample, style, maxLines = 1, softWrap = false).size.width.toDp() }
  }
  // 2dp start + 6dp end padding inside the gutter cell + 2dp breathing room.
  return textWidth + 10.dp
}

/**
 * Unified diff with old/new line numbers, syntax colors and an optional shared horizontal scroll.
 */
@Composable
fun DiffLineRow(
  line: DiffLine, text: AnnotatedString?, gutter: Dp, modifier: Modifier = Modifier,
  scrollState: ScrollState? = null, codeWidth: Dp = 0.dp,
  /** Has a thread; drawn as an accent bar at the start of the line. */
  commented: Boolean = false,
  /** Starts a comment on this line; null when commenting isn't possible here. */
  onClick: (() -> Unit)? = null,
) {
  val c = Gl.colors
  val options = LocalDiffDisplayOptions.current
  val numberColumns = if (!options.showLineNumbers) 0 else if (options.dualLineNumbers) 2 else 1
  if (line.kind == DiffKind.Hunk) {
    Text(
      text = line.text,
      style = Gl.type.mono.copy(fontSize = options.fontSize.sp),
      color = c.accent,
      modifier = modifier
        .fillMaxWidth()
        .background(c.diffHunkBg)
        .padding(start = gutter * numberColumns + 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    )
    return
  }
  val (bg, marker) = when (line.kind) {
    DiffKind.Add -> c.diffAddBg to "+"
    DiffKind.Remove -> c.diffRemoveBg to "−"
    else -> Color.Transparent to " "
  }
  val markColor = c.accent
  Row(
    modifier = modifier
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable(onClickLabel = "Comment on this line", onClick = onClick) else Modifier)
      // Drawn over the line (not behind it), or the add/remove background below would cover it.
      .drawWithContent {
        drawContent()
        if (commented) drawRect(markColor, size = Size(4.dp.toPx(), size.height))
      }
      .then(if (scrollState != null) Modifier.horizontalScroll(scrollState).width(codeWidth) else Modifier)
      .height(IntrinsicSize.Min)
      .background(bg)
      .semantics(mergeDescendants = true) {
        contentDescription = when (line.kind) {
          DiffKind.Add -> "Added line ${line.newNo}: ${line.text}"
          DiffKind.Remove -> "Removed line ${line.oldNo}: ${line.text}"
          else -> "Line ${line.newNo}: ${line.text}"
        } + if (commented) ". Has comments" else ""
      },
  ) {
    val numbers = when {
      !options.showLineNumbers -> emptyList()
      options.dualLineNumbers -> listOf(line.oldNo, line.newNo)
      else -> listOf(if (line.kind == DiffKind.Remove) line.oldNo else line.newNo)
    }
    for (number in numbers) {
      Text(
        text = number?.toString().orEmpty(),
        style = Gl.type.mono.copy(fontSize = (options.fontSize - 1).sp),
        color = when (line.kind) { DiffKind.Add -> c.success; DiffKind.Remove -> c.danger; else -> c.textTertiary },
        maxLines = 1,
        modifier = Modifier.width(gutter).padding(start = 2.dp, end = 6.dp, top = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
      )
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
    Text(
      text = marker,
      style = Gl.type.monoSmall,
      color = when (line.kind) {
        DiffKind.Add -> c.success
        DiffKind.Remove -> c.danger
        else -> c.textTertiary
      },
      modifier = Modifier.padding(start = 4.dp, top = 3.dp),
    )
    Text(
      text = diffDisplayText(
        if (options.syntaxColors) text ?: AnnotatedString(line.text) else AnnotatedString(line.text),
        wrapped = scrollState == null,
      ),
      style = Gl.type.mono.copy(fontSize = options.fontSize.sp, lineHeight = (options.fontSize * 1.5).sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
      color = c.textPrimary,
      softWrap = scrollState == null,
      modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
    )
  }
}

/** File bar above a diff: collapse caret, type icon, path, a "viewed" box and the change count. */
@Composable
fun DiffFileHeader(
  diff: GitLabFileDiff,
  collapsed: Boolean,
  viewed: Boolean,
  onToggleCollapsed: () -> Unit,
  onToggleViewed: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val c = Gl.colors
  val options = LocalDiffDisplayOptions.current
  val path = diff.newPath.ifEmpty { diff.oldPath }
  val name = path.substringAfterLast('/')
  val shown = if (diff.renamedFile && diff.oldPath != diff.newPath) "${diff.oldPath} → ${diff.newPath}" else path
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(c.surface)
      .clickable(role = Role.Button, onClickLabel = if (collapsed) "Show changes" else "Hide changes", onClick = onToggleCollapsed)
      .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
      .defaultMinSize(minHeight = 52.dp)
      .padding(start = 8.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      if (collapsed) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown,
      contentDescription = null,
      tint = c.textSecondary,
      modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = shown,
      style = Gl.type.mono,
      color = c.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).then(if (options.scrollablePath) Modifier.horizontalScroll(rememberScrollState()) else Modifier).semantics { contentDescription = shown },
    )
    if (collapsed) Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
      when {
        diff.newFile -> GlBadge("New", tone = Tone.Success)
        diff.deletedFile -> GlBadge("Deleted", tone = Tone.Danger)
        diff.renamedFile -> GlBadge("Renamed", tone = Tone.Info)
        diff.generatedFile -> GlBadge("Generated")
      }
      val stats = remember(diff) { diffStats(listOf(diff)) }
      Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        Text("+${stats.added}", style = Gl.type.monoSmall, color = c.success)
        Text("−${stats.removed}", style = Gl.type.monoSmall, color = c.danger)
      }
    }
    if (onToggleViewed != null) {
      IconButton(onClick = onToggleViewed) {
        Icon(
          if (viewed) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
          contentDescription = if (viewed) "Viewed, mark as not viewed" else "Mark as viewed",
          tint = c.accent,
        )
      }
    } else {
      Spacer(Modifier.width(8.dp))
    }
  }
}

/** Keeps the end of a long path, which says more than its start: "…nel/AGENTS.md". */
fun ellipsizeStart(text: String, max: Int): String =
  if (text.length <= max) text else "…" + text.takeLast(max - 1)

/**
 * A list of changed files for a LazyColumn: a sticky header per file, then its lines as
 * separate items so a huge change only composes what is on screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun LazyListScope.diffFileItems(
  diffs: List<GitLabFileDiff>,
  parsed: Map<Int, List<DiffLine>>,
  prepared: Map<Int, List<AnnotatedString>>,
  collapsed: Set<Int>,
  viewed: Set<Int>,
  onToggleCollapsed: (Int) -> Unit,
  onToggleViewed: ((Int) -> Unit)?,
  wrapLines: Boolean = true,
  selectedFile: Int? = null,
  /** Keys from [diffLineKey] of lines that have threads. */
  commented: Set<String> = emptySet(),
  /** Tapping a line starts a comment on it; null keeps lines inert. */
  onLineClick: ((file: Int, line: DiffLine) -> Unit)? = null,
) {
  diffs.forEachIndexed { index, diff ->
    if (selectedFile != null && selectedFile != index) return@forEachIndexed
    stickyHeader(key = "file-$index") {
      Column {
        DiffFileHeader(
          diff = diff,
          collapsed = index in collapsed,
          viewed = index in viewed,
          onToggleCollapsed = { onToggleCollapsed(index) },
          onToggleViewed = onToggleViewed?.let { { it(index) } },
        )
        GlDivider()
      }
    }
    if (index in collapsed) return@forEachIndexed
    val lines = parsed[index]
    when {
      diff.diff.isBlank() -> item(key = "file-$index-empty") {
        Text(
          when {
            diff.tooLarge -> "GitLab excluded this diff because it is too large. View the file in GitLab."
            diff.collapsed -> "GitLab omitted this diff. Open it in GitLab to load the changes."
            diff.renamedFile -> "File renamed without text changes."
            else -> "No text diff available (binary file or metadata-only change)."
          },
          style = Gl.type.meta,
          color = Gl.colors.textSecondary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      lines == null -> item(key = "file-$index-loading") { LoadingState(label = "Loading changes") }
      else -> {
        val maxLine = lines.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0
        val texts = prepared[index]
        val maxColumns = (lines.maxOfOrNull { it.text.length } ?: 0).coerceAtMost(MAX_DIFF_COLUMNS)
        items(lines.size, key = { "file-$index-line-$it" }) { n ->
          val gutter = rememberDiffGutterWidth(maxLine)
          val scroll = if (wrapLines) null else LocalDiffScrollStates.current.getOrPut(index) { ScrollState(0) }
          val screenWidth = LocalConfiguration.current.screenWidthDp.dp
          val options = LocalDiffDisplayOptions.current
          val fontScale = LocalDensity.current.fontScale
          val width = remember(lines, screenWidth, gutter, options, fontScale) {
            val gutters = gutter * (if (!options.showLineNumbers) 0 else if (options.dualLineNumbers) 2 else 1)
            diffCodeWidth(maxColumns, options.fontSize.toFloat(), fontScale, gutters, screenWidth)
          }
          val line = lines[n]
          DiffLineRow(
            line, texts?.getOrNull(n), gutter, scrollState = scroll, codeWidth = width,
            commented = commented.isNotEmpty() && diffLineKey(diff.oldPath, diff.newPath, line.oldNo, line.newNo) in commented,
            onClick = if (onLineClick != null && line.kind != DiffKind.Hunk) ({ onLineClick(index, line) }) else null,
          )
        }
      }
    }
    item(key = "file-$index-gap") { Spacer(Modifier.height(12.dp).fillMaxWidth().background(Gl.colors.sunken)) }
  }
}

/** Parsed lines and their highlighted text, filled in file by file off the main thread. */
class PreparedDiffs {
  val parsed = mutableStateMapOf<Int, List<DiffLine>>()
  val texts = mutableStateMapOf<Int, List<AnnotatedString>>()
}

/**
 * Parses every file first (plain text shows at once), then highlights them in order, so a change
 * touching hundreds of files never blocks the screen.
 */
@Composable
fun rememberPreparedDiffs(diffs: List<GitLabFileDiff>): PreparedDiffs {
  val colors = Gl.colors
  val holder = remember(diffs, colors) { PreparedDiffs() }
  LaunchedEffect(holder) {
    diffs.forEachIndexed { index, diff ->
      holder.parsed[index] = withContext(Dispatchers.Default) { parseUnifiedDiff(diff.diff) }
    }
    diffs.forEachIndexed { index, diff ->
      val lines = holder.parsed[index] ?: return@forEachIndexed
      val path = diff.newPath.ifEmpty { diff.oldPath }
      holder.texts[index] = withContext(Dispatchers.Default) { prepareDiffLines(lines, path, colors) }
    }
  }
  return holder
}

val LocalDiffScrollStates = androidx.compose.runtime.compositionLocalOf { mutableMapOf<Int, ScrollState>() }

private const val DIFF_PREVIEW_LINES = 120

/** Unified diff for a single file, inline in a page. Long files start collapsed to a preview. */
@Composable
fun DiffViewer(diffText: String, modifier: Modifier = Modifier, path: String = "") {
  val c = Gl.colors
  val lines = remember(diffText) { parseUnifiedDiff(diffText) }
  var expanded by rememberSaveable(diffText.hashCode()) { mutableStateOf(false) }
  val visible = if (expanded || lines.size <= DIFF_PREVIEW_LINES) lines else lines.take(DIFF_PREVIEW_LINES)
  var texts by remember(diffText, c, expanded) { mutableStateOf<List<AnnotatedString>?>(null) }
  LaunchedEffect(diffText, c, expanded) {
    texts = withContext(Dispatchers.Default) { prepareDiffLines(visible, path, c) }
  }
  val gutter = rememberDiffGutterWidth(lines.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0)
  Column(modifier = modifier.fillMaxWidth()) {
    visible.forEachIndexed { index, line -> DiffLineRow(line, texts?.getOrNull(index), gutter) }
    if (lines.size > DIFF_PREVIEW_LINES) {
      GlButton(
        text = if (expanded) "Show fewer lines" else "Show all ${lines.size} lines",
        onClick = { expanded = !expanded },
        kind = GlButtonKind.Ghost,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
