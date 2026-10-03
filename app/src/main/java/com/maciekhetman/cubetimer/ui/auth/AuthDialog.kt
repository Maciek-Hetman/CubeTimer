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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.currentUser
import com.maciekhetman.cubetimer.viewmodel.AuthViewModel

@Composable
fun AuthDialog(
    formState: AuthFormState,
    authState: AuthState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
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
                onDismiss = onDismiss,
                onNavigateToLogin = { viewModel.openDialog(AuthDialogType.LOGIN) },
                onNavigateToForgotPassword = { viewModel.openDialog(AuthDialogType.FORGOT_PASSWORD) },
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
        AuthDialogType.VERIFY_EMAIL_LINK -> {
            VerifyEmailLinkDialog(
                formState = formState,
                authState = authState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        AuthDialogType.RESET_PASSWORD_LINK -> {
            ResetPasswordLinkDialog(
                formState = formState,
                authState = authState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        AuthDialogType.USER_PROFILE -> {
            UserProfileDialog(
                authState = authState,
                formState = formState,
                viewModel = viewModel,
                onDismiss = onDismiss,
                modifier = modifier
            )
        }
        AuthDialogType.DELETE_ACCOUNT -> {
            DeleteAccountDialog(
                formState = formState,
                viewModel = viewModel,
                onCancel = { viewModel.openDialog(AuthDialogType.USER_PROFILE) },
                modifier = modifier
            )
        }
        AuthDialogType.CHANGE_PASSWORD -> {
            ChangePasswordDialog(
                formState = formState,
                viewModel = viewModel,
                onCancel = viewModel::cancelChangePassword,
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
        title = { Text(stringResource(R.string.auth_sign_in)) },
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
                    value = formState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text(stringResource(R.string.auth_email)) },
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
                    label = { Text(stringResource(R.string.auth_password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePasswordVisibility) {
                            Icon(
                                imageVector = if (formState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.auth_toggle_password_visibility)
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
                        text = stringResource(R.string.auth_forgot_password_link),
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
                        text = stringResource(R.string.auth_no_account) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.auth_register),
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
                Text(stringResource(R.string.auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
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
        title = { Text(stringResource(R.string.auth_create_account)) },
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
                    label = { Text(stringResource(R.string.auth_email)) },
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
                    label = { Text(stringResource(R.string.auth_password_min)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePasswordVisibility) {
                            Icon(
                                imageVector = if (formState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.auth_toggle_password_visibility)
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
                    label = { Text(stringResource(R.string.auth_confirm_password)) },
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
                        text = stringResource(R.string.auth_have_account) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.auth_sign_in),
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
                Text(stringResource(R.string.auth_register))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
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
        title = { Text(stringResource(R.string.auth_forgot_password_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.auth_forgot_password_message),
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
                    label = { Text(stringResource(R.string.auth_email)) },
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
                        text = stringResource(R.string.auth_remember_password) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.auth_sign_in),
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
                Text(stringResource(R.string.auth_send_link))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier
    )
}

/**
 * Shown after a reset was requested. The emailed link opens the web client, where the new password
 * is chosen; the app only points the user there and back to sign-in.
 */
@Composable
private fun ResetPasswordDialog(
    formState: AuthFormState,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    val email = formState.email.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.auth_check_email_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // The server answers the same way whether or not the address has an account.
                Text(
                    text = stringResource(
                        R.string.auth_reset_link_sent,
                        email.ifEmpty { stringResource(R.string.auth_reset_link_sent_fallback) }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.auth_didnt_get_it) + " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.auth_send_again),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onNavigateToForgotPassword)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = onNavigateToLogin
            ) {
                Text(stringResource(R.string.auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
        modifier = modifier
    )
}

/**
 * Shown after registering, and when a sign-in is refused because the email is unverified. The
 * emailed link opens the web client, which verifies the account; the user then signs in here.
 */
@Composable
private fun EmailVerificationDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val email = formState.email.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.auth_verify_email_title)) },
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

                Text(
                    text = stringResource(
                        R.string.auth_verification_sent,
                        email.ifEmpty { stringResource(R.string.auth_verification_sent_fallback) }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auth_verification_spam_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    shape = RoundedCornerShape(20.dp),
                    onClick = viewModel::submitResendVerification,
                    enabled = !formState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (formState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.auth_resend_email))
                }
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = onNavigateToLogin,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun UserProfileDialog(
    authState: AuthState,
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onDismiss: () -> Unit,
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
                Text(stringResource(R.string.auth_profile_title))
            }
        },
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

                val user = authState.currentUser
                when {
                    user != null -> {
                        ProfileInfoCard(
                            email = user.email,
                            isVerified = user.emailVerified
                        )
                    }
                    authState is AuthState.Guest -> {
                        Text(
                            text = stringResource(R.string.auth_guest_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    shape = RoundedCornerShape(20.dp),
                    onClick = viewModel::adoptGuestData,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.auth_import_local_solves))
                }

                if (user != null) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        shape = RoundedCornerShape(20.dp),
                        onClick = { viewModel.openDialog(AuthDialogType.CHANGE_PASSWORD) },
                        enabled = !formState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.auth_change_password))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        shape = RoundedCornerShape(20.dp),
                        onClick = { viewModel.openDialog(AuthDialogType.DELETE_ACCOUNT) },
                        enabled = !formState.isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.auth_delete_account))
                    }
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
                Text(stringResource(R.string.auth_sign_out))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun DeleteAccountDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        // An in-flight deletion must not be dismissed from under its own result (or error).
        onDismissRequest = { if (!formState.isLoading) onCancel() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.auth_delete_account_title)) },
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

                Text(
                    text = stringResource(R.string.auth_delete_account_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auth_delete_account_local_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(20.dp),
                onClick = viewModel::submitDeleteAccount,
                enabled = !formState.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.auth_delete_permanently))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onCancel,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier
    )
}

/**
 * Changing the password ends every session of the user on the server; the app signs in again with
 * the new password behind the scenes, so the user only sees the result on the profile (or sign-in).
 */
@Composable
private fun ChangePasswordDialog(
    formState: AuthFormState,
    viewModel: AuthViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val passwordTransformation =
        if (formState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        // An in-flight change must not be dismissed from under its own result (or error).
        onDismissRequest = { if (!formState.isLoading) onCancel() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.auth_change_password)) },
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
                    value = formState.currentPassword,
                    onValueChange = viewModel::onCurrentPasswordChanged,
                    label = { Text(stringResource(R.string.auth_current_password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePasswordVisibility) {
                            Icon(
                                imageVector = if (formState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.auth_toggle_password_visibility)
                            )
                        }
                    },
                    visualTransformation = passwordTransformation,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = formState.currentPasswordError != null,
                    supportingText = formState.currentPasswordError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = formState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text(stringResource(R.string.auth_new_password_min)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = passwordTransformation,
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
                    label = { Text(stringResource(R.string.auth_confirm_new_password)) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = passwordTransformation,
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
                onClick = viewModel::submitChangePassword,
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
                Text(stringResource(R.string.auth_change_password))
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onCancel,
                enabled = !formState.isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ProfileInfoCard(
    email: String,
    isVerified: Boolean
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
                        label = { Text(stringResource(R.string.auth_badge_verified)) },
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
            }
        }
    }
}

@Composable
internal fun ErrorBanner(message: String) {
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
