package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import com.cboyxd.gitlab.ui.components.InlineError
import com.cboyxd.gitlab.ui.theme.Gl

/** Asks which branch or tag to run a new pipeline on; the project's current ref is prefilled. */
@Composable
fun RunPipelineDialog(
  defaultRef: String,
  onRun: (ref: String, onResult: (String?) -> Unit) -> Unit,
  onDismiss: () -> Unit,
) {
  val c = Gl.colors
  var ref by rememberSaveable { mutableStateOf(defaultRef) }
  var running by rememberSaveable { mutableStateOf(false) }
  var error by rememberSaveable { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = { if (!running) onDismiss() },
    containerColor = c.surface,
    title = { Text("Run pipeline", style = Gl.type.title, color = c.textPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Runs the project's CI/CD configuration for this branch or tag.", style = Gl.type.bodySmall, color = c.textSecondary)
        OutlinedTextField(
          value = ref,
          onValueChange = { ref = it; error = null },
          label = { Text("Branch or tag") },
          singleLine = true,
          enabled = !running,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
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
      }
    },
    confirmButton = {
      GlButton(
        text = "Run",
        onClick = {
          if (ref.isBlank()) {
            error = "Enter a branch or tag."
          } else {
            running = true
            onRun(ref.trim()) { result ->
              running = false
              if (result == null) onDismiss() else error = result
            }
          }
        },
        loading = running,
        modifier = Modifier.testTag("run_pipeline_confirm"),
      )
    },
    dismissButton = { GlButton(text = "Cancel", onClick = onDismiss, kind = GlButtonKind.Ghost, enabled = !running) },
  )
}
