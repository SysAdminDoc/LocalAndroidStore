package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun StatusBadge(status: CardStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        CardStatus.NotInstalled -> "Available" to Catppuccin.Sapphire
        CardStatus.Installed -> "Installed" to Catppuccin.Green
        CardStatus.UpdateAvailable -> "Update available" to Catppuccin.Yellow
        CardStatus.Working -> "Working…" to Catppuccin.Mauve
        CardStatus.Error -> "Error" to Catppuccin.Red
        CardStatus.SignatureMismatch -> "Key mismatch" to Catppuccin.Red
        CardStatus.PermissionReview -> "Review required" to Catppuccin.Peach
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = Catppuccin.Crust,
        modifier = modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun Color.lightOn(): Color = this
