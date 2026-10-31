package io.github.marukon.imeauto.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marukon.imeauto.R
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.data.model.ImeGroup
import io.github.marukon.imeauto.data.model.ImeItem

@Composable
fun ImeSelectDialog(
    title: String,
    currentSelectedId: String?,
    isImeApp: Boolean = false,
    allowDefaultOption: Boolean,
    imes: List<ImeItem>,
    groups: List<ImeGroup> = emptyList(),
    onManageGroupsClick: (() -> Unit)? = null,
    onSelectIme: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                if (allowDefaultOption) {
                    // Option 1: Follow Global Default
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectIme(null) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSelectedId == null,
                                onClick = { onSelectIme(null) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isImeApp) "默认规则 (输入法应用默认保持不变)" else "不修改",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = if (isImeApp) "输入法本身无需频繁切出，默认不自动切换" else "不进行自动切换，保持当前输入法状态不变",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Option 2: Explicitly Keep Current
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectIme(ConfigManager.RULE_KEEP_CURRENT) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSelectedId == ConfigManager.RULE_KEEP_CURRENT,
                                onClick = { onSelectIme(ConfigManager.RULE_KEEP_CURRENT) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "保持当前不变 (不自动切换)",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = "进入该应用时不执行任何切换，保持原输入法状态",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }

                    // Section 3: Groups (Profiles for Batch Switching)
                    if (groups.isNotEmpty() || onManageGroupsClick != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "应用分组 (批量管理)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (onManageGroupsClick != null) {
                                    TextButton(onClick = onManageGroupsClick) {
                                        Text(text = "管理分组", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        items(groups, key = { "group_${it.id}" }) { group ->
                            val groupKey = "${ConfigManager.PREFIX_GROUP}${group.id}"
                            val targetImeName = if (group.targetImeId == ConfigManager.RULE_KEEP_CURRENT || group.targetImeId.isEmpty()) {
                                "保持不变"
                            } else {
                                imes.firstOrNull { it.id == group.targetImeId }?.label ?: group.targetImeId
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectIme(groupKey) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentSelectedId == groupKey,
                                    onClick = { onSelectIme(groupKey) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                    )
                                    Text(
                                        text = "指定目标: $targetImeName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    item {
                        Text(
                            text = "单独指定输入法",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                }

                items(imes, key = { it.id }) { ime ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectIme(ime.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSelectedId == ime.id,
                            onClick = { onSelectIme(ime.id) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = ime.label,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                text = ime.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}
