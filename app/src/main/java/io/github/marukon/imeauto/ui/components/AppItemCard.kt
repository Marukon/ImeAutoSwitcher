package io.github.marukon.imeauto.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.data.model.AppItem
import io.github.marukon.imeauto.data.model.ImeGroup
import io.github.marukon.imeauto.data.model.ImeItem

@Composable
fun AppItemCard(
    app: AppItem,
    allImes: List<ImeItem>,
    groups: List<ImeGroup>,
    onSelectImeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayRuleText = remember(app.assignedImeId, app.isImeApp, allImes, groups) {
        val rule = app.assignedImeId
        when {
            rule == null -> {
                if (app.isImeApp) "保持不变 (默认)" else "跟随默认"
            }
            rule == ConfigManager.RULE_KEEP_CURRENT -> {
                "保持不变"
            }
            rule.startsWith(ConfigManager.PREFIX_GROUP) -> {
                val groupId = rule.removePrefix(ConfigManager.PREFIX_GROUP)
                val group = groups.firstOrNull { it.id == groupId }
                if (group != null) {
                    val imeName = if (group.targetImeId == ConfigManager.RULE_KEEP_CURRENT || group.targetImeId.isEmpty()) {
                        "保持不变"
                    } else {
                        allImes.firstOrNull { it.id == group.targetImeId }?.label ?: group.targetImeId
                    }
                    "[${group.name}] $imeName"
                } else {
                    "未知分组"
                }
            }
            else -> {
                allImes.firstOrNull { it.id == rule }?.label ?: rule
            }
        }
    }

    val isCustomized = app.assignedImeId != null

    val iconBitmap = remember(app.icon) {
        app.icon?.let { drawable ->
            try {
                val width = drawable.intrinsicWidth.coerceIn(48, 128)
                val height = drawable.intrinsicHeight.coerceIn(48, 128)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCustomized) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.name,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // App Name & Package Name & Badges
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    // Type Badge
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (app.isSystem) "系统" else "用户",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(width = 48.dp, height = 20.dp)
                    )

                    // IME special badge
                    if (app.isImeApp) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "输入法",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            modifier = Modifier.size(width = 56.dp, height = 20.dp)
                        )
                    }

                    if (!app.isLaunchable) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "无图标",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.size(width = 56.dp, height = 20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Assigned IME Selector Chip
            AssistChip(
                onClick = onSelectImeClick,
                label = {
                    Text(
                        text = displayRuleText,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isCustomized) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (app.isImeApp) {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    labelColor = if (isCustomized) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else if (app.isImeApp) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
