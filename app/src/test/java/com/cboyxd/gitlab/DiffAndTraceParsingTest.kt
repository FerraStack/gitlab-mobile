package com.cboyxd.gitlab

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.cboyxd.gitlab.data.model.GitLabFileDiff
import com.cboyxd.gitlab.ui.components.DiffKind
import com.cboyxd.gitlab.ui.components.DiffStats
import com.cboyxd.gitlab.ui.components.diffStats
import com.cboyxd.gitlab.ui.components.parseUnifiedDiff
import com.cboyxd.gitlab.ui.components.rawNewLine
import com.cboyxd.gitlab.ui.screens.JobConsoleBackground
import com.cboyxd.gitlab.ui.screens.JobConsoleForeground
import com.cboyxd.gitlab.ui.screens.parseJobTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for unified diff parsing, word-diff highlighting, stats, and ANSI job trace parsing (#62). */
class DiffAndTraceParsingTest {

  @Test
  fun `parseUnifiedDiff handles empty input and ignores header lines before first hunk`() {
    assertTrue(parseUnifiedDiff("").isEmpty())

    val withPreface = """
      --- a/file.kt
      +++ b/file.kt
      @@ -10,3 +20,3 @@ fun demo()
       context
      -removed	tab
      +added	tab
      \ No newline at end of file
    """.trimIndent()

    val lines = parseUnifiedDiff(withPreface)
    assertEquals(5, lines.size)

    assertEquals(DiffKind.Hunk, lines[0].kind)
    assertEquals("@@ -10,3 +20,3 @@ fun demo()", lines[0].text)
    assertNull(lines[0].oldNo)
    assertNull(lines[0].newNo)

    assertEquals(DiffKind.Context, lines[1].kind)
    assertEquals(10, lines[1].oldNo)
    assertEquals(20, lines[1].newNo)
    assertEquals("context", lines[1].text)

    assertEquals(DiffKind.Remove, lines[2].kind)
    assertEquals(11, lines[2].oldNo)
    assertNull(lines[2].newNo)
    assertEquals("removed    tab", lines[2].text)

    assertEquals(DiffKind.Add, lines[3].kind)
    assertNull(lines[3].oldNo)
    assertEquals(21, lines[3].newNo)
    assertEquals("added    tab", lines[3].text)

    assertEquals(DiffKind.Hunk, lines[4].kind)
    assertEquals("No newline at end of file", lines[4].text)

    // rawNewLine preserves original tab instead of the 4-space display expansion.
    assertEquals("added\ttab", rawNewLine(withPreface, 21))
    assertEquals("context", rawNewLine(withPreface, 20))
    assertNull(rawNewLine(withPreface, 99))
  }

  @Test
  fun `parseUnifiedDiff resets line numbers across multiple hunks`() {
    val diff = """
      @@ -1,2 +1,2 @@
       first
      -second
      +SECOND
      @@ -50,2 +51,3 @@
       fifty
      +inserted
       fifty-one
    """.trimIndent()

    val lines = parseUnifiedDiff(diff)
    assertEquals(7, lines.size)
    assertEquals(1, lines[1].oldNo)
    assertEquals(1, lines[1].newNo)
    assertEquals(2, lines[2].oldNo)
    assertEquals(2, lines[3].newNo)

    assertEquals(DiffKind.Hunk, lines[4].kind)
    assertEquals(50, lines[5].oldNo)
    assertEquals(51, lines[5].newNo)
    assertNull(lines[6].oldNo)
    assertEquals(52, lines[6].newNo)
  }

  @Test
  fun `parseUnifiedDiff marks word-level changes only for matching edited pairs`() {
    val diff = """
      @@ -1,6 +1,6 @@
      -val count = 1
      +val count = 20
      -completelydifferent
      +unrelatedrewrite
      -a
      -b
      +x
    """.trimIndent()

    val lines = parseUnifiedDiff(diff)
    val removeEdited = lines[1]
    val addEdited = lines[2]
    assertEquals(12..12, removeEdited.changed) // "1"
    assertEquals(12..13, addEdited.changed) // "20"

    // Completely rewritten lines share no prefix/suffix, so no word highlight is added.
    assertNull(lines[3].changed)
    assertNull(lines[4].changed)

    // Asymmetric 2-removed vs 1-added run leaves word changes unmarked.
    assertNull(lines[5].changed)
    assertNull(lines[6].changed)
    assertNull(lines[7].changed)
  }

  @Test
  fun `diffStats counts additions and removals across files and ignores pre-hunk lines`() {
    val diffs = listOf(
      GitLabFileDiff(
        oldPath = "a.kt",
        newPath = "a.kt",
        diff = "--- a/a.kt\n+++ b/a.kt\n@@ -1,2 +1,3 @@\n keep\n-old\n+new1\n+new2\n",
      ),
      GitLabFileDiff(
        oldPath = "b.kt",
        newPath = "b.kt",
        diff = "@@ -5,2 +5,1 @@\n-gone\n+stay\n",
      ),
    )

    assertEquals(DiffStats(added = 3, removed = 2), diffStats(diffs))
  }

  @Test
  fun `parseJobTrace strips runner timestamp prefixes and exposes HH MM SS ms timestamps`() {
    val raw = "2026-11-15T08:12:34.123456Z 00O+ first\n2026-11-15T08:12:35.999Z 01E second\n"
    val parsed = parseJobTrace(raw)

    assertEquals(2, parsed.size)
    assertEquals(1, parsed[0].number)
    assertEquals(0L, parsed[0].byteOffset)
    assertEquals("08:12:34.123", parsed[0].timestamp)
    assertEquals("first", parsed[0].text.text)

    assertEquals(2, parsed[1].number)
    assertEquals("08:12:35.999", parsed[1].timestamp)
    assertEquals("second", parsed[1].text.text)
    assertEquals(raw.substringBefore('\n').length + 1L, parsed[1].byteOffset)
  }

  @Test
  fun `parseJobTrace renders SGR attributes, extended colors, and persists styles across newlines`() {
    val raw = "\u001B[1;31merr\nstillboldred\u001B[22;39m plain " +
      "\u001B[3;4;9mdeco\u001B[0m " +
      "\u001B[38;5;196;48:2::10:20:30mext\u001B[0m " +
      "\u001B[7minv\u001B[27m " +
      "\u001B[8mhid"

    val parsed = parseJobTrace(raw)
    assertEquals(2, parsed.size)

    // Bold shifts basic palette index 1 (0xFFDF6D6D) to bright red (0xFFFF6161).
    val line0Span = parsed[0].text.spanStyles.single()
    assertEquals(FontWeight.Bold, line0Span.item.fontWeight)
    assertEquals(Color(0xFFFF6161), line0Span.item.color)

    // Still active at start of line 1 until \u001B[22;39m resets it.
    val line1 = parsed[1].text
    val spanContinued = line1.spanStyles.first { line1.text.substring(it.start, it.end) == "stillboldred" }
    assertEquals(FontWeight.Bold, spanContinued.item.fontWeight)
    assertEquals(Color(0xFFFF6161), spanContinued.item.color)

    val spanPlain = line1.spanStyles.first { line1.text.substring(it.start, it.end) == " plain " }
    assertNull(spanPlain.item.fontWeight)
    assertEquals(JobConsoleForeground, spanPlain.item.color)

    val spanDeco = line1.spanStyles.first { line1.text.substring(it.start, it.end) == "deco" }
    assertEquals(FontStyle.Italic, spanDeco.item.fontStyle)
    assertEquals(TextDecoration.Underline + TextDecoration.LineThrough, spanDeco.item.textDecoration)

    val spanExt = line1.spanStyles.first { line1.text.substring(it.start, it.end) == "ext" }
    // Xterm 256-color index 196 = rgb(255, 0, 0); 24-bit bg = rgb(10, 20, 30)
    assertEquals(Color(255, 0, 0), spanExt.item.color)
    assertEquals(Color(10, 20, 30), spanExt.item.background)

    val spanInv = line1.spanStyles.first { line1.text.substring(it.start, it.end) == "inv" }
    assertEquals(JobConsoleBackground, spanInv.item.color)
    assertEquals(JobConsoleForeground, spanInv.item.background)

    val spanHid = line1.spanStyles.first { line1.text.substring(it.start, it.end) == "hid" }
    assertEquals(JobConsoleBackground, spanHid.item.color)
  }

  @Test
  fun `parseJobTrace handles carriage return redraws and strips OSC hyperlinks`() {
    val raw = "\u001B[32m10%\r50%\r100%\n\u001B]8;;https://gitlab.example\u0007click\u001B]8;;\u0007"
    val parsed = parseJobTrace(raw)

    assertEquals(2, parsed.size)
    assertEquals("100%", parsed[0].text.text)
    // Green foreground set before first \r carries over into the redrawn "100%".
    assertEquals(Color(0xFF4BB349), parsed[0].text.spanStyles.single().item.color)
    assertEquals("click", parsed[1].text.text)
  }

  @Test
  fun `parseJobTrace tracks collapsible and nested sections`() {
    val raw = """
      section_start:100:step_script[collapsed=true]
      outer line
      section_start:101:inner_step
      inner title
      nested line
      section_end:102:step_script
      after
    """.trimIndent()

    val parsed = parseJobTrace(raw)
    assertEquals(6, parsed.size)

    // Blank section_start line falls back to humanized section title.
    assertEquals("100:step_script", parsed[0].sectionKey)
    assertEquals("Script", parsed[0].text.text)
    assertTrue(parsed[0].initiallyCollapsed)
    assertTrue(parsed[0].parents.isEmpty())

    assertEquals(listOf("100:step_script"), parsed[1].parents)
    assertEquals("101:inner_step", parsed[2].sectionKey)
    assertFalse(parsed[2].initiallyCollapsed)
    assertEquals(listOf("100:step_script"), parsed[2].parents)

    assertEquals("inner title", parsed[3].text.text)
    assertEquals(listOf("100:step_script", "101:inner_step"), parsed[3].parents)
    assertEquals("nested line", parsed[4].text.text)
    assertEquals(listOf("100:step_script", "101:inner_step"), parsed[4].parents)

    // Closing outer section also closes inner section, and blank section_end is omitted.
    assertEquals("after", parsed[5].text.text)
    assertTrue(parsed[5].parents.isEmpty())
  }

  @Test
  fun `parseJobTrace honors startOffset and firstLineNumber`() {
    val parsed = parseJobTrace("a\nb\n", startOffset = 500L, firstLineNumber = 42)
    assertEquals(2, parsed.size)
    assertEquals(42, parsed[0].number)
    assertEquals(500L, parsed[0].byteOffset)
    assertEquals(43, parsed[1].number)
    assertEquals(502L, parsed[1].byteOffset)
  }
}
