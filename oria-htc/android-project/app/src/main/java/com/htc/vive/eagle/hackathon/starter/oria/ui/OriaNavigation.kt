package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.htc.vive.eagle.hackathon.starter.ui.tab.AppDestination

@Composable
fun OriaNavigation(currentRoute: String?, recording: Boolean, onSelect: (AppDestination) -> Unit) {
    OriaUiTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDestination.echoItems.forEach { destination ->
                    val selected = currentRoute == destination.route
                    val hasCapture = destination == AppDestination.OriaLab && recording
                    Surface(
                        modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                            .semantics { if (hasCapture) stateDescription = "Enregistrement en cours" },
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(destination.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center)
                            if (hasCapture) Box(Modifier.size(8.dp).clip(MaterialTheme.shapes.extraSmall)
                                .background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}
