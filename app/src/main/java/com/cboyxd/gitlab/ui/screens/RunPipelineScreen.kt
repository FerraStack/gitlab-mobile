package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.ui.components.GlButton
import com.cboyxd.gitlab.ui.components.GlTopBar
import com.cboyxd.gitlab.ui.components.InlineError
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace

/**
 * A page of its own, like GitLab's "Run pipeline" form: the branch or tag to run on, with the
 * project's current ref prefilled. Room for CI/CD variables later.
 */
@Composable
fun RunPipelineScreen(
  projectPath: String?,
  defaultRef: String,
  onRun: (ref: String, onResult: (String?) -> Unit) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val c = Gl.colors
  var ref by rememberSaveable { mutableStateOf(defaultRef) }
  var running by rememberSaveable { mutableStateOf(false) }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  val run = {
    if (ref.isBlank()) {
      error = "Enter a branch or tag."
    } else if (!running) {
      running = true
      onRun(ref.trim()) { result ->
        running = false
        error = result
      }
    }
  }
  Column(modifier = modifier.fillMaxSize().background(c.bg).imePadding()) {
    GlTopBar(title = "Run pipeline", overline = projectPath, onBack = onBack)
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GlSpace.margin),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "Runs the project's CI/CD configuration for a branch or tag. The new pipeline opens when it starts.",
        style = Gl.type.bodySmall,
        color = c.textSecondary,
      )
      OutlinedTextField(
        value = ref,
        onValueChange = { ref = it; error = null },
        label = { Text("Branch or tag") },
        singleLine = true,
        enabled = !running,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { run() }),
        colors = OutlinedTextFieldDefaults.colors(
          focusedTextColor = c.textPrimary,
          unfocusedTextColor = c.textPrimary,
          focusedBorderColor = c.accent,
          unfocusedBorderColor = c.borderStrong,
          focusedLabelColor = c.accent,
          unfocusedLabelColor = c.textSecondary,
          cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth().testTag("pipeline_ref"),
      )
      error?.let { InlineError(it) }
      GlButton(
        text = "Run pipeline",
        onClick = run,
        loading = running,
        icon = Icons.Outlined.PlayArrow,
        modifier = Modifier.fillMaxWidth().testTag("run_pipeline_confirm"),
      )
    }
  }
}
