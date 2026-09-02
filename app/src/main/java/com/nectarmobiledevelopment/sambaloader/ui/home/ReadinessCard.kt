package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessItem
import com.nectarmobiledevelopment.sambaloader.core.system.ReadinessStatus

/**
 * The device-permission dashboard.
 *
 * Deliberately shows the whole list, not just the failures: a user who has
 * fixed everything needs to see that it is fixed, and someone diagnosing
 * "why did nothing upload" needs to see what was checked, not infer it
 * from silence.
 */
@Composable
fun ReadinessCard(
    uiState: HomeUiState,
    onFix: (ReadinessItem) -> Unit,
) {
    if (uiState.readiness.isEmpty()) {
        return
    }
    val needsAttention = uiState.readinessNeedingAttention.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (needsAttention) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = CONTAINER_TINT),
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Device permissions", style = MaterialTheme.typography.titleMedium)
                Text(uiState.readinessSummary, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            if (needsAttention) {
                Text(
                    "Tap anything below to fix it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for (item in uiState.readiness) {
                ReadinessRow(item = item, onFix = { onFix(item) })
            }
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.isActionable) Modifier.clickable(onClick = onFix) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(item.status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.check.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.needsAttention) FontWeight.Bold else FontWeight.Normal,
            )
            // Only explain what is wrong. Repeating the rationale for the
            // things that already work turns the card into a wall of text.
            val detail = item.detail ?: if (item.needsAttention) item.check.problem else null
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = if (item.isActionable) item.check.actionLabel else item.status.label(),
            style = MaterialTheme.typography.labelLarge,
            color = if (item.isActionable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun StatusDot(status: ReadinessStatus) {
    Surface(
        modifier = Modifier.size(DOT_SIZE),
        shape = CircleShape,
        color = status.colour(),
        content = {},
    )
}

/**
 * Colour carries the severity at a glance, but never alone — every row
 * also carries a word, so this reads correctly for colour-blind users.
 */
@Composable
private fun ReadinessStatus.colour(): Color = when (this) {
    ReadinessStatus.OK -> OK_GREEN
    ReadinessStatus.WARNING -> WARNING_AMBER
    ReadinessStatus.CRITICAL -> MaterialTheme.colorScheme.error
    ReadinessStatus.NOT_NEEDED -> MaterialTheme.colorScheme.surfaceVariant
}

private fun ReadinessStatus.label(): String = when (this) {
    ReadinessStatus.OK -> "On"
    ReadinessStatus.WARNING -> "Off"
    ReadinessStatus.CRITICAL -> "Off"
    ReadinessStatus.NOT_NEEDED -> "Not needed"
}

private val OK_GREEN = Color(0xFF2E7D32)
private val WARNING_AMBER = Color(0xFFF9A825)
private val DOT_SIZE = 12.dp
private const val CONTAINER_TINT = 0.4f
