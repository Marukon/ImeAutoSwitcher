package io.github.marukon.imeauto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.data.model.ImeGroup
import io.github.marukon.imeauto.data.model.ImeItem

@Composable
fun GroupManagerDialog(
    groups: List<ImeGroup>,
    allImes: List<ImeItem>,
    onAddGroup: (name: String, targetImeId: String) -> Unit,
    onUpdateGroup: (ImeGroup) -> Unit,
    onDeleteGroup: (groupId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ImeGroup?>(null) }
    var groupToPickImeFor by remember { mutableStateOf<ImeGroup?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "应用分组管理",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建分组",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "将多个应用加入同一分组，修改分组输入法即可批量自动切换全部关联应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (groups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无自定义分组，点击右上角「+」创建",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(groups, key = { it.id }) { group ->
                            val targetImeLabel = if (group.targetImeId == ConfigManager.RULE_KEEP_CURRENT || group.targetImeId.isEmpty()) {
                                "保持当前不变"
                            } else {
                                allImes.firstOrNull { it.id == group.targetImeId }?.label ?: group.targetImeId
                            }

                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
                                        )
                                        Text(
                                            text = "指派: $targetImeLabel (点击更改)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { groupToPickImeFor = group }
                                        )
                                    }
                                    IconButton(onClick = { editingGroup = group }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "重命名",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onDeleteGroup(group.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(text = "完成")
            }
        }
    )

    // Sub-dialog: Create new group
    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var selectedImeId by remember { mutableStateOf(allImes.firstOrNull()?.id ?: "") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(text = "新建应用分组") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("分组名称 (如: 办公、游戏)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "选择初始目标输入法：",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        item {
                            Text(
                                text = "保持当前不变",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedImeId = ConfigManager.RULE_KEEP_CURRENT }
                                    .padding(vertical = 6.dp),
                                color = if (selectedImeId == ConfigManager.RULE_KEEP_CURRENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        items(allImes, key = { it.id }) { ime ->
                            Text(
                                text = ime.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedImeId = ime.id }
                                    .padding(vertical = 6.dp),
                                color = if (selectedImeId == ime.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onAddGroup(newName.trim(), selectedImeId)
                            showCreateDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text(text = "创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    // Sub-dialog: Rename group
    editingGroup?.let { group ->
        var rename by remember { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { editingGroup = null },
            title = { Text(text = "重命名分组") },
            text = {
                OutlinedTextField(
                    value = rename,
                    onValueChange = { rename = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rename.isNotBlank()) {
                            onUpdateGroup(group.copy(name = rename.trim()))
                            editingGroup = null
                        }
                    },
                    enabled = rename.isNotBlank()
                ) {
                    Text(text = "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingGroup = null }) {
                    Text(text = "取消")
                }
            }
        )
    }

    // Sub-dialog: Pick IME for existing group
    groupToPickImeFor?.let { group ->
        ImeSelectDialog(
            title = "为 [${group.name}] 选择批量目标输入法",
            currentSelectedId = group.targetImeId,
            allowDefaultOption = false,
            imes = allImes,
            onSelectIme = { newImeId ->
                onUpdateGroup(group.copy(targetImeId = newImeId ?: ConfigManager.RULE_KEEP_CURRENT))
                groupToPickImeFor = null
            },
            onDismiss = { groupToPickImeFor = null }
        )
    }
}
