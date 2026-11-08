package com.cboyxd.gitlab.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minified one-line files must not make a diff row wider than Compose can measure. */
class DiffLayoutTest {
  // Compose's largest representable width; a 10,000-column row at density 3 used to exceed it.
  private val maxPx = 262_143f

  @Test
  fun `the widest possible row stays measurable at the largest settings`() {
    val width = diffCodeWidth(longestLine = 200_000, fontSizeSp = 18f, fontScale = 2f, gutters = 120.dp, screenWidth = 400.dp)
    assertTrue("${width.value} dp", width.value * 4f < maxPx)
  }

  @Test
  fun `short lines use the screen width`() {
    assertEquals(400.dp, diffCodeWidth(longestLine = 20, fontSizeSp = 14f, fontScale = 1f, gutters = 40.dp, screenWidth = 400.dp))
  }

  @Test
  fun `long lines are cut with an ellipsis`() {
    val long = AnnotatedString("x".repeat(20_000))
    val scrolled = diffDisplayText(long, wrapped = false)
    assertEquals(MAX_DIFF_COLUMNS + 1, scrolled.length)
    assertTrue(scrolled.text.endsWith("…"))
    assertEquals(MAX_DIFF_WRAPPED_CHARS + 1, diffDisplayText(long, wrapped = true).length)
    assertEquals("short", diffDisplayText(AnnotatedString("short"), wrapped = false).text)
  }
}
