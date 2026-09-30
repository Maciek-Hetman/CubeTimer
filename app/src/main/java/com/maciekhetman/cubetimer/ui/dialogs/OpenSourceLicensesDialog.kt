package com.maciekhetman.cubetimer.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A third-party component bundled with the app and the license it ships under. */
private data class LicensedComponent(val name: String, val license: String)

/** Keep in step with the `implementation` dependencies in `app/build.gradle.kts`. */
private val BundledComponents = listOf(
    LicensedComponent("TNoodle scrambles", "GPL-3.0"),
    LicensedComponent("AndroidX (Compose, Room, DataStore, WorkManager, Security)", "Apache-2.0"),
    LicensedComponent("Material Icons", "Apache-2.0"),
    LicensedComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
    LicensedComponent("OkHttp, Retrofit", "Apache-2.0"),
    LicensedComponent("Tink", "Apache-2.0"),
    LicensedComponent("desugar_jdk_libs", "GPL-2.0 with Classpath exception"),
)

/**
 * The app's own license (GPL-3.0, which TNoodle requires) and the licenses of the components it
 * bundles. [onViewLicense] opens the full license text.
 */
@Composable
fun OpenSourceLicensesDialog(
    onViewLicense: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Open-source licenses") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Cube Timer is free software, released under the GNU General Public License " +
                        "v3.0. It comes with no warranty.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Third-party components",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                BundledComponents.forEach { component ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = component.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onViewLicense
            ) {
                Text("View GPL-3.0")
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
