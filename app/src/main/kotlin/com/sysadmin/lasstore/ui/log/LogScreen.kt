package com.sysadmin.lasstore.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmin.lasstore.data.LogLevel
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val entries by ServiceLocator.logger.entries.collectAsStateWithLifecycle()
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(modifier = Modifier.fillMaxSize().background(Catppuccin.Crust)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Activity log",
                style = MaterialTheme.typography.titleLarge,
                color = Catppuccin.Mauve,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { ServiceLocator.logger.clear() }) {
                    Text("Clear", color = Catppuccin.Subtext)
                }
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity yet.", color = Catppuccin.Subtext)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = false,
            ) {
                items(entries.reversed(), key = { "${it.ts}-${it.tag}-${it.message.hashCode()}" }) { entry ->
                    val color = when (entry.level) {
                        LogLevel.Info -> Catppuccin.Sapphire
                        LogLevel.Warn -> Catppuccin.Yellow
                        LogLevel.Error -> Catppuccin.Red
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Catppuccin.Surface0, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Row {
                            Text(
                                text = df.format(Date(entry.ts)),
                                color = Catppuccin.Subtext,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "  [${entry.level.name.uppercase()}]  ${entry.tag}",
                                color = color,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = entry.message,
                            color = Catppuccin.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
