package com.maciekhetman.cubetimer.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CreateSessionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var sessionName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("New Session") },
        text = {
            Column {
                Text(
                    text = "Create a custom session for your solves.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = {
                        sessionName = it
                        if (errorText != null) errorText = null
                    },
                    label = { Text("Session Name") },
                    placeholder = { Text("e.g. 3x3 Practice, CFOP Training") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = {
                    val trimmed = sessionName.trim()
                    when {
                        trimmed.isBlank() -> errorText = "Session name cannot be empty"
                        trimmed.length > 64 -> errorText = "Name must be 64 characters or less"
                        else -> {
                            onConfirm(trimmed)
                            onDismiss()
                        }
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}
