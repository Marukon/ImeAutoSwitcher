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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                if (app.isImeApp) "保持不变" else "不修改"
            }
            rule == ConfigManager.RULE_KEEP_CURRENT -> {
                "保持不变"
            }
            rule.startsWith(ConfigManager.PREFIX_GROUP) -> {
                val groupId = rule.removePrefix(ConfigManager.PREFIX_GROUP)
                val group = groups.firstOrNull { it.id == groupId }
                if (group != null) {
                    "📁 ${group.name}"
                } else {
                    "未知分组"
                }
            }
            else -> {
                allImes.firstOrNull { it.id == rule }?.label ?: rule
            }
        }
    }

    // IME apps only count as customized if explicitly assigned to another IME or group
    val isCustomized = if (app.isImeApp) {
        app.assignedImeId != null && app.assignedImeId != ConfigManager.RULE_KEEP_CURRENT
    } else {
        app.assignedImeId != null
    }

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
                        .size(40.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // App Info: Name, Package, Badges
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
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

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Type Badge
                    AppBadge(
                        text = if (app.isSystem) "系统" else "用户",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // IME special badge
                    if (app.isImeApp) {
                        AppBadge(
                            text = "输入法",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    // No-launcher badge
                    if (!app.isLaunchable) {
                        AppBadge(
                            text = "无图标",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Assigned IME / Group Selector Pill
            Surface(
                onClick = onSelectImeClick,
                shape = RoundedCornerShape(8.dp),
                color = when {
                    isCustomized -> MaterialTheme.colorScheme.primaryContainer
                    app.isImeApp -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                contentColor = when {
                    isCustomized -> MaterialTheme.colorScheme.onPrimaryContainer
                    app.isImeApp -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.widthIn(min = 60.dp, max = 130.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = displayRuleText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(containerColor)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            color = contentColor
        )
    }
}
