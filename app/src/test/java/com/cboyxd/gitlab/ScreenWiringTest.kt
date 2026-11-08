package com.cboyxd.gitlab

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens give their action callbacks no-op defaults so tests and previews stay short. That also
 * means forgetting to pass one where the app shows the screen still compiles, and the button just
 * does nothing (thread replies and resolve shipped like that). This reads the sources and fails
 * when a screen is shown without one of those callbacks.
 */
class ScreenWiringTest {

  private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.associate { it.path to it.readText() }

  private val declaration = Regex("""\n(?:internal )?fun (\w+Screen)\(\n(.*?)\n\) \{""", RegexOption.DOT_MATCHES_ALL)
  private val defaultedCallback = Regex("""^\s*(on\w+): [^\n]*= \{[^\n]*}""", RegexOption.MULTILINE)

  @Test
  fun `every screen the app shows gets all of its action callbacks`() {
    val problems = mutableListOf<String>()
    var checked = 0
    for (text in sources.values) {
      for (screen in declaration.findAll(text)) {
        val name = screen.groupValues[1]
        val callbacks = defaultedCallback.findAll(screen.groupValues[2]).map { it.groupValues[1] }.toList()
        if (callbacks.isEmpty()) continue
        for ((path, caller) in sources) {
          for (call in Regex("""(?<!fun )\b$name\(""").findAll(caller)) {
            val arguments = argumentsAt(caller, call.range.last)
            val missing = callbacks.filterNot { Regex("""\b$it\s*=""").containsMatchIn(arguments) }
            if (missing.isNotEmpty()) problems += "${File(path).name}: $name(...) doesn't pass $missing"
            checked++
          }
        }
      }
    }
    assertTrue("found no screen calls; did the sources move?", checked > 0)
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }

  /** The text between the parenthesis at [open] and its partner. */
  private fun argumentsAt(text: String, open: Int): String {
    var depth = 0
    for (i in open until text.length) {
      when (text[i]) {
        '(' -> depth++
        ')' -> if (--depth == 0) return text.substring(open, i)
      }
    }
    return text.substring(open)
  }
}
