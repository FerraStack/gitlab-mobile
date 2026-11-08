package com.cboyxd.gitlab

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.ui.components.DiffKind
import com.cboyxd.gitlab.ui.components.DiffLine
import com.cboyxd.gitlab.ui.components.DiffLineRow
import com.cboyxd.gitlab.ui.components.LocalDiffDisplayOptions
import com.cboyxd.gitlab.ui.components.DiffDisplayOptions
import com.cboyxd.gitlab.ui.components.diffCodeWidth
import com.cboyxd.gitlab.ui.components.diffGutterWidth
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A minified one-line file in a diff, unwrapped and wrapped. It used to crash when unwrapped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LongDiffLineScreenshotTest {

  @get:Rule val rule = createComposeRule()

  private val minified = (1..1_500).joinToString("") { "function f$it(a){return a*$it};" }
  private val lines = listOf(
    DiffLine(DiffKind.Hunk, null, null, "@@ -1 +1 @@"),
    DiffLine(DiffKind.Remove, 1, null, "var old=1;"),
    DiffLine(DiffKind.Add, null, 1, minified),
    DiffLine(DiffKind.Context, 2, 2, "// end"),
  )

  @Composable
  private fun Rows(wrap: Boolean) {
    val gutter = diffGutterWidth(2)
    val scroll = ScrollState(0)
    CompositionLocalProvider(LocalDiffDisplayOptions provides DiffDisplayOptions(wrapLines = wrap)) {
      Column(Modifier.fillMaxSize().background(Gl.colors.bg)) {
        Text(if (wrap) "Wrapped" else "Unwrapped", color = Gl.colors.textSecondary)
        lines.forEach { line ->
          DiffLineRow(
            line, null, gutter,
            scrollState = if (wrap) null else scroll,
            codeWidth = diffCodeWidth(minified.length, 14f, 1f, gutter, 400.dp),
          )
        }
      }
    }
  }

  private fun capture(name: String, dark: Boolean, wrap: Boolean) {
    rule.setContent { GitLabTheme(darkTheme = dark, reducedMotion = true) { Rows(wrap) } }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Test fun unwrapped_light() = capture("diff_long_line_unwrapped", dark = false, wrap = false)
  @Test fun unwrapped_dark() = capture("diff_long_line_unwrapped", dark = true, wrap = false)
  @Test fun wrapped_light() = capture("diff_long_line_wrapped", dark = false, wrap = true)
  @Test fun wrapped_dark() = capture("diff_long_line_wrapped", dark = true, wrap = true)
}
