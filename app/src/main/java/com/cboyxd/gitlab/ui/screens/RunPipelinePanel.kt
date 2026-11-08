package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import com.cboyxd.gitlab.ui.components.GlButtonKind
import com.cboyxd.gitlab.ui.components.GlCard
import com.cboyxd.gitlab.ui.components.InlineError
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace

/**
 * Inline form above a project's pipelines: which branch or tag to run a new pipeline on, with the
 * project's current ref prefilled. Inline rather than a dialog, so it stays one tap from the list.
 */
@Composable
fun RunPipelinePanel(
  defaultRef: String,
  onRun: (ref: String, onResult: (String?) -> Unit) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val c = Gl.colors
  var ref by rememberSaveable { mutableStateOf(defaultRef) }
  var running by rememberSaveable { mutableStateOf(false) }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  val run = {
    if (ref.isBlank()) {
      error = "Enter a branch or tag."
    } else {
      running = true
      onRun(ref.trim()) { result ->
        running = false
        if (result == null) onDismiss() else error = result
      }
    }
  }
  GlCard(modifier = modifier.padding(horizontal = GlSpace.margin, vertical = GlSpace.tight), contentPadding = PaddingValues(GlSpace.margin)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Run pipeline", style = Gl.type.sectionTitle, color = c.textPrimary)
      Text("Runs the project's CI/CD configuration for this branch or tag.", style = Gl.type.bodySmall, color = c.textSecondary)
      OutlinedTextField(
        value = ref,
        onValueChange = { ref = it; error = null },
        label = { Text("Branch or tag") },
        singleLine = true,
        enabled = !running,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (!running) run() }),
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
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlSpace.tight, Alignment.End)) {
        GlButton(text = "Cancel", onClick = onDismiss, kind = GlButtonKind.Ghost, enabled = !running)
        GlButton(text = "Run", onClick = run, loading = running, icon = Icons.Outlined.PlayArrow, modifier = Modifier.testTag("run_pipeline_confirm"))
      }
    }
  }
}
