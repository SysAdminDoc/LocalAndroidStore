package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun StatusBadge(status: CardStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        CardStatus.NotInstalled -> "AVAILABLE" to Catppuccin.Sapphire
        CardStatus.Installed -> "INSTALLED" to Catppuccin.Mint
        CardStatus.UpdateAvailable -> "UPDATE" to Catppuccin.Peach
        CardStatus.Working -> "WORKING" to Catppuccin.MauveStrong
        CardStatus.Error -> "ERROR" to Catppuccin.Red
        CardStatus.SignatureMismatch -> "KEY MISMATCH" to Catppuccin.Red
        CardStatus.PermissionReview -> "REVIEW" to Catppuccin.Peach
    }

    Surface(
        modifier = modifier,
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
