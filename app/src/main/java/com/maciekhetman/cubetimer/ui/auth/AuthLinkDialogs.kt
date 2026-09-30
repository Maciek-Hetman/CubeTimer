package com.maciekhetman.cubetimer.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.viewmodel.AuthViewModel

// Dialogs opened from a link in a CubeSync email (see AuthLink). Completing either one signs this
// device in to the account the link belongs to and adopts its guest solves, and a link can come
// from anyone, so both wait for the user to confirm and neither acts while someone is signed in.

private const val ONLY_IF_REQUESTED = "Only continue if you asked for this email."

private fun AuthState.signedInEmail(): String? = when (this) {
    is AuthState.Authenticated -> user.email
    is AuthState.Admin -> user.email
    is AuthState.Guest, is AuthState.Loading -> null
}

@Composable
internal fun VerifyEmailLinkDialog(
    formState: AuthFormState,
    authState: AuthState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signedInEmail = authState.signedInEmail()
    AlertDialog(
        // An in-flight verification must not be dismissed from under its own result (or error).
        onDismissRequest = { if (!formState.isLoading) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Verify your email") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (formState.errorMessage != null) {
                    ErrorBanner(formState.errorMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (signedInEmail != null) {
                    SignedInNotice(signedInEmail)
                } else {
                    Text(
                        text = "Finish verifying your email and sign in on this device?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Solves saved on this device will be added to that account. $ONLY_IF_REQUESTED",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (signedInEmail == null) {
                Button(
                    shape = RoundedCornerShape(20.dp),
                    onClick = viewModel::submitVerifyEmail,
                    // Still restoring a stored session (Loading): it may turn out someone is signed in.
                    enabled = !formState.isLoading && authState is AuthState.Guest
                ) {
                    if (formState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Verify and sign in")
                }
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text(if (signedInEmail != null) "Close" else "Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
internal fun ResetPasswordLinkDialog(
    formState: AuthFormState,
    authState: AuthState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signedInEmail = authState.signedInEmail()
    AlertDialog(
        onDismissRequest = { if (!formState.isLoading) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Choose a new password") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (formState.errorMessage != null) {
                    ErrorBanner(formState.errorMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (signedInEmail != null) {
                    SignedInNotice(signedInEmail)
                } else {
                    OutlinedTextField(
                        value = formState.password,
                        onValueChange = viewModel::onPasswordChanged,
                        label = { Text("New password (min 10 characters)") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = viewModel::togglePasswordVisibility) {
                                Icon(
                                    imageVector = if (formState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        visualTransformation = if (formState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = formState.passwordError != null,
                        supportingText = formState.passwordError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = formState.confirmPassword,
                        onValueChange = viewModel::onConfirmPasswordChanged,
                        label = { Text("Confirm new password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = if (formState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = formState.confirmPasswordError != null,
                        supportingText = formState.confirmPasswordError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "You'll be signed in on this device. $ONLY_IF_REQUESTED",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (signedInEmail == null) {
                Button(
                    shape = RoundedCornerShape(20.dp),
                    onClick = viewModel::submitResetPassword,
                    enabled = !formState.isLoading && authState is AuthState.Guest
                ) {
                    if (formState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Set password")
                }
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text(if (signedInEmail != null) "Close" else "Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun SignedInNotice(email: String) {
    Text(
        text = "You're signed in as $email. Sign out first to use this link.",
        style = MaterialTheme.typography.bodyMedium
    )
}
