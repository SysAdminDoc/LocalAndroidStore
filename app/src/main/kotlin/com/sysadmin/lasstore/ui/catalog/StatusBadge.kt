package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun StatusBadge(status: CardStatus, modifier: Modifier = Modifier) {
    val (labelRes, color) = when (status) {
        CardStatus.NotInstalled -> R.string.status_available to Catppuccin.Sapphire
        CardStatus.Unmanaged -> R.string.status_unmanaged to Catppuccin.Peach
        CardStatus.Installed -> R.string.status_installed to Catppuccin.Mint
        CardStatus.Archived -> R.string.status_archived to Catppuccin.Sapphire
        CardStatus.ReleaseAvailable -> R.string.status_new_release to Catppuccin.Sapphire
        CardStatus.UpdateAvailable -> R.string.status_update to Catppuccin.Peach
        CardStatus.ReinstallAvailable -> R.string.status_reinstall to Catppuccin.Sapphire
        CardStatus.DowngradeAvailable -> R.string.status_downgrade to Catppuccin.Peach
        CardStatus.Working -> R.string.status_working to Catppuccin.MauveStrong
        CardStatus.Error -> R.string.status_error to Catppuccin.Red
        CardStatus.SignatureMismatch -> R.string.status_key_mismatch to Catppuccin.Red
        CardStatus.PermissionReview -> R.string.status_review to Catppuccin.Peach
    }
    val label = stringResource(labelRes)
    val accessibilityLabel = stringResource(R.string.status_badge_description, label)

    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = accessibilityLabel
        },
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.48f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
