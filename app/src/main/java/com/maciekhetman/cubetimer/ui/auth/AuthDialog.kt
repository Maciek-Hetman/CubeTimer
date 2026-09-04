package com.maciekhetman.cubetimer.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.viewmodel.AuthViewModel

@Composable
fun AuthDialog(
    formState: AuthFormState,
    authState: AuthState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onOpenAdminDashboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (formState.dialogType == AuthDialogType.NONE) return

    when (formState.dialogType) {
        AuthDialogType.LOGIN -> {
            LoginDialog(
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onNavigateToRegister = { viewModel.openDialog(AuthDialogType.REGISTER) },
                onNavigateToForgotPassword = { viewModel.openDialog(AuthDialogType.FORGOT_PASSWORD) },
                modifier = modifier
            )
        }
        AuthDialogType.REGISTER -> {
            RegisterDialog(
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onNavigateToLogin = { viewModel.openDialog(AuthDialogType.LOGIN) },
                modifier = modifier
            )
        }
        AuthDialogType.FORGOT_PASSWORD -> {
            ForgotPasswordDialog(
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onNavigateToLogin = { viewModel.openDialog(AuthDialogType.LOGIN) },
                modifier = modifier
            )
        }
        AuthDialogType.RESET_PASSWORD -> {
            ResetPasswordDialog(
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onNavigateToLogin = { viewModel.openDialog(AuthDialogType.LOGIN) },
                modifier = modifier
            )
        }
        AuthDialogType.EMAIL_VERIFICATION -> {
            EmailVerificationDialog(
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onNavigateToLogin = { viewModel.openDialog(AuthDialogType.LOGIN) },
                modifier = modifier
            )
        }
        AuthDialogType.USER_PROFILE -> {
            UserProfileDialog(
                authState = authState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onOpenAdminDashboard = onOpenAdminDashboard,
                modifier = modifier
            )
        }
        AuthDialogType.NONE -> Unit
    }
}

@Composable
private fun LoginDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Sign In") },
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

                OutlinedTextField(
                    value = formState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = formState.emailError != null,
                    supportingText = formState.emailError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = formState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text("Password") },
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

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Forgot password?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onNavigateToForgotPassword)
                            .padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Don't have an account? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Register",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onNavigateToRegister)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitLogin,
                enabled = !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Sign In")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun RegisterDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Create Account") },
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

                OutlinedTextField(
                    value = formState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = formState.emailError != null,
                    supportingText = formState.emailError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = formState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text("Password (min 10 characters)") },
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
                    label = { Text("Confirm Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (formState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = formState.confirmPasswordError != null,
                    supportingText = formState.confirmPasswordError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Already have an account? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onNavigateToLogin)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitRegister,
                enabled = !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Register")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ForgotPasswordDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Forgot Password") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Enter your account email to receive password reset instructions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (formState.errorMessage != null) {
                    ErrorBanner(formState.errorMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = formState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = formState.emailError != null,
                    supportingText = formState.emailError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Remember your password? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onNavigateToLogin)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitForgotPassword,
                enabled = !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Send Instructions")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ResetPasswordDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Reset Password") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (formState.successMessage != null) {
                    SuccessBanner(formState.successMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (formState.errorMessage != null) {
                    ErrorBanner(formState.errorMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = formState.token,
                    onValueChange = viewModel::onTokenChanged,
                    label = { Text("Reset Token") },
                    singleLine = true,
                    isError = formState.tokenError != null,
                    supportingText = formState.tokenError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = formState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text("New Password (min 10 chars)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
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
                    label = { Text("Confirm New Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (formState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = formState.confirmPasswordError != null,
                    supportingText = formState.confirmPasswordError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitResetPassword,
                enabled = !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Set Password")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun EmailVerificationDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Verify Email") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (formState.successMessage != null) {
                    SuccessBanner(formState.successMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (formState.errorMessage != null) {
                    ErrorBanner(formState.errorMessage)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = formState.token,
                    onValueChange = viewModel::onTokenChanged,
                    label = { Text("Verification Token") },
                    singleLine = true,
                    isError = formState.tokenError != null,
                    supportingText = formState.tokenError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitVerifyEmail,
                enabled = !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Verify")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun UserProfileDialog(
    authState: AuthState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onOpenAdminDashboard: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Account Profile")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (authState) {
                    is AuthState.Authenticated -> {
                        ProfileInfoCard(
                            email = authState.user.email,
                            isVerified = authState.user.isEmailVerified,
                            isAdmin = false
                        )
                    }
                    is AuthState.Admin -> {
                        ProfileInfoCard(
                            email = authState.user.email,
                            isVerified = authState.user.isEmailVerified,
                            isAdmin = true
                        )

                        if (onOpenAdminDashboard != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                shape = RoundedCornerShape(20.dp),
                                onClick = {
                                    onDismiss()
                                    onOpenAdminDashboard()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Admin Metrics Dashboard")
                            }
                        }
                    }
                    is AuthState.Guest -> {
                        Text(
                            text = "Guest Mode",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> Unit
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    shape = RoundedCornerShape(20.dp),
                    onClick = viewModel::adoptGuestData,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Unsynced Local Solves")
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Sign Out")
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text("Close")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ProfileInfoCard(
    email: String,
    isVerified: Boolean,
    isAdmin: Boolean
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = email,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isVerified) {
                    AssistChip(
                        onClick = {},
                        shape = RoundedCornerShape(16.dp),
                        label = { Text("VERIFIED") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }

                if (isAdmin) {
                    AssistChip(
                        onClick = {},
                        shape = RoundedCornerShape(16.dp),
                        label = { Text("ADMIN") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun SuccessBanner(message: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(10.dp)
        )
    }
}
