package com.cboyxd.gitlab.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cboyxd.gitlab.ui.components.ErrorState
import com.cboyxd.gitlab.ui.components.GlButton
import com.cboyxd.gitlab.ui.components.GlButtonKind
import com.cboyxd.gitlab.ui.theme.Gl

/**
 * Shown when saved accounts can't be decrypted. Retrying rarely helps (the key is gone, for
 * example after moving to a new phone), so it also offers to remove them and start over.
 */
@Composable
fun StorageErrorState(message: String, onRetry: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
  var confirming by rememberSaveable { mutableStateOf(false) }
  Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    ErrorState(message = message, onRetry = onRetry)
    Text(
      "If this keeps happening, for example after moving to a new phone, remove the saved accounts and sign in again.",
      style = Gl.type.bodySmall,
      color = Gl.colors.textSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 32.dp),
    )
    Spacer(Modifier.height(16.dp))
    GlButton(
      text = "Remove saved accounts",
      onClick = { confirming = true },
      kind = GlButtonKind.Danger,
      modifier = Modifier.testTag("reset_accounts"),
    )
  }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      containerColor = Gl.colors.surface,
      title = { Text("Remove saved accounts?", style = Gl.type.sectionTitle, color = Gl.colors.textPrimary) },
      text = {
        Text(
          "The app forgets every saved account on this phone and opens as a guest; then you can sign in again. " +
            "Tokens stay valid on their servers. Revoke them in GitLab if you no longer need them.",
          style = Gl.type.body,
          color = Gl.colors.textSecondary,
        )
      },
      confirmButton = {
        TextButton(onClick = { confirming = false; onReset() }, modifier = Modifier.testTag("reset_accounts_confirm")) {
          Text("Remove", color = Gl.colors.danger)
        }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
  }
}
