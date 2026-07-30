package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
internal fun PublisherTrustRecoveryDialog(
    details: PublisherTrustDetails,
    onDismiss: () -> Unit,
    onConfirm: (typedApplicationId: String, independentlyVerified: Boolean) -> Unit,
) {
    val metadata = details.downloadedMetadata
    var stage by rememberSaveable(metadata.applicationId, metadata.signingSha256) {
        mutableIntStateOf(TRUST_DETAILS_STAGE)
    }
    var typedApplicationId by rememberSaveable(metadata.applicationId, metadata.signingSha256) {
        mutableStateOf("")
    }
    var independentlyVerified by rememberSaveable(metadata.applicationId, metadata.signingSha256) {
        mutableStateOf(false)
    }
    val contentScroll = rememberScrollState()
    LaunchedEffect(stage) {
        contentScroll.scrollTo(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .testTag("publisherTrustRecoveryDialog")
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .then(
                        if (stage == TRUST_DETAILS_STAGE) {
                            Modifier.fillMaxHeight(0.94f)
                        } else {
                            Modifier
                        },
                    ),
                shape = RoundedCornerShape(26.dp),
                color = Catppuccin.PanelRaised,
                border = BorderStroke(1.dp, Catppuccin.Red.copy(alpha = 0.42f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (stage == TRUST_DETAILS_STAGE) {
                                Icons.Default.Security
                            } else {
                                Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = Catppuccin.Red,
                        )
                        Column {
                            Text(
                                text = if (stage == TRUST_DETAILS_STAGE) {
                                    "Publisher trust details"
                                } else {
                                    "Final trust replacement"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = Catppuccin.TextStrong,
                            )
                            Text(
                                text = "This does not install the APK.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Subtext,
                            )
                        }
                    }

                    HorizontalDivider(color = Catppuccin.Stroke)

                    Column(
                        modifier = Modifier
                            .then(
                                if (stage == TRUST_DETAILS_STAGE) {
                                    Modifier.weight(1f)
                                } else {
                                    Modifier.heightIn(max = 440.dp)
                                },
                            )
                            .verticalScroll(contentScroll),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        if (stage == TRUST_DETAILS_STAGE) {
                            Text(
                                text = "The downloaded APK is cryptographically valid, but its current " +
                                    "publisher key is unrelated to the stored pin. This can indicate a " +
                                    "repository compromise, a substituted APK, or legitimate key loss.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Catppuccin.Red,
                            )
                            TrustValue("Source", details.source)
                            TrustValue("Package", metadata.applicationId)
                            TrustFingerprint(
                                label = "Installed signer",
                                value = details.installedSignerSha256
                                    ?: "Not installed or current signer unavailable",
                            )
                            TrustFingerprint("Stored publisher pin", details.storedPinSha256)
                            TrustFingerprint("Downloaded APK signer", metadata.signingSha256)
                            TrustValue(
                                "Verified signature schemes",
                                metadata.verifiedSignatureSchemes
                                    .map { it.name.lowercase().replace("31", "3.1") }
                                    .sorted()
                                    .joinToString()
                                    .ifEmpty { "None" },
                            )
                            TrustValue(
                                "Verified proof-of-rotation lineage",
                                if (metadata.lineageSha256.isEmpty()) {
                                    "No lineage was reported."
                                } else {
                                    metadata.lineageSha256
                                        .mapIndexed { index, fingerprint ->
                                            "${index + 1}. $fingerprint"
                                        }
                                        .joinToString("\n")
                                },
                                monospace = metadata.lineageSha256.isNotEmpty(),
                            )
                            OutlinedTextField(
                                value = typedApplicationId,
                                onValueChange = { typedApplicationId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Type the exact package id") },
                                supportingText = {
                                    Text(metadata.applicationId)
                                },
                                singleLine = true,
                            )
                        } else {
                            Text(
                                text = "This only changes LocalAndroidStore's trust record; it does " +
                                    "not install the APK. Replacing this pin discards the existing publisher " +
                                    "continuity for ${metadata.applicationId}. Android may still reject " +
                                    "the update if the installed app uses the old key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Catppuccin.Red,
                            )
                            TrustFingerprint("Pin being removed", details.storedPinSha256)
                            TrustFingerprint("New trusted pin", metadata.signingSha256)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        independentlyVerified = !independentlyVerified
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = independentlyVerified,
                                    onCheckedChange = { independentlyVerified = it },
                                )
                                Text(
                                    text = "I independently verified this new publisher fingerprint " +
                                        "outside LocalAndroidStore and accept the loss of continuity.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Catppuccin.Text,
                                    modifier = Modifier.padding(top = 11.dp),
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Catppuccin.Stroke)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (stage == TRUST_DETAILS_STAGE) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { stage = FINAL_ACKNOWLEDGEMENT_STAGE },
                                enabled = canAdvancePublisherPinRecovery(
                                    metadata.applicationId,
                                    typedApplicationId,
                                ),
                            ) {
                                Text("Continue")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    stage = TRUST_DETAILS_STAGE
                                    independentlyVerified = false
                                },
                            ) {
                                Text("Back")
                            }
                            Button(
                                onClick = {
                                    onConfirm(typedApplicationId, independentlyVerified)
                                },
                                enabled = canReplacePublisherPin(
                                    details,
                                    typedApplicationId,
                                    independentlyVerified,
                                ),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Catppuccin.Red,
                                    contentColor = Catppuccin.Crust,
                                ),
                            ) {
                                Text(
                                    text = "Replace publisher pin",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustFingerprint(label: String, value: String) {
    TrustValue(label = label, value = value, monospace = true)
}

@Composable
private fun TrustValue(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Catppuccin.MauveStrong,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Text,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

private const val TRUST_DETAILS_STAGE = 0
private const val FINAL_ACKNOWLEDGEMENT_STAGE = 1
