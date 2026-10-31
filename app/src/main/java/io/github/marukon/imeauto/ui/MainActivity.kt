package io.github.marukon.imeauto.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marukon.imeauto.R
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.ui.components.AboutDialog
import io.github.marukon.imeauto.ui.components.AppItemCard
import io.github.marukon.imeauto.ui.components.GroupManagerDialog
import io.github.marukon.imeauto.ui.components.ImeSelectDialog
import io.github.marukon.imeauto.ui.components.TopStatusCard
import io.github.marukon.imeauto.ui.theme.ImeAutoSwitcherTheme
import io.github.marukon.imeauto.ui.viewmodel.AppFilterTab
import io.github.marukon.imeauto.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.checkModuleStatus(this)
        ConfigManager.notifyHook(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ImeAutoSwitcherTheme {
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.init(context)
                }

                val globalEnabled by viewModel.globalEnabled.collectAsState()
                val defaultImeId by viewModel.defaultImeId.collectAsState()
                val hideNoLauncher by viewModel.hideNoLauncher.collectAsState()
                val hideLauncherIcon by viewModel.hideLauncherIcon.collectAsState()
                val aggressiveRecheck by viewModel.aggressiveRecheck.collectAsState()
                val isModuleActive by viewModel.isModuleActive.collectAsState()
                val selectedTab by viewModel.selectedTab.collectAsState()
                val selectedFilter by viewModel.selectedFilter.collectAsState()
                val usedImeFilters by viewModel.usedImeFilters.collectAsState()
                val groupFilters by viewModel.groupFilters.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val apps by viewModel.filteredApps.collectAsState()
                val imes by viewModel.imes.collectAsState()
                val groups by viewModel.groups.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val selectedAppForImePick by viewModel.selectedAppForImePick.collectAsState()
                val isPickingDefaultIme by viewModel.isPickingDefaultIme.collectAsState()

                var showMenu by remember { mutableStateOf(false) }
                var showGroupManagerDialog by remember { mutableStateOf(false) }
                var showAboutDialog by remember { mutableStateOf(false) }
                var showHideIconConfirmDialog by remember { mutableStateOf(false) }

                // Backup: Export JSON
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                val jsonStr = viewModel.exportConfig(context)
                                stream.write(jsonStr.toByteArray(Charsets.UTF_8))
                            }
                            Toast.makeText(context, "配置导出成功", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "导出失败: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Restore: Import JSON
                val importLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        try {
                            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader(Charsets.UTF_8).readText()
                            }
                            if (!content.isNullOrEmpty()) {
                                val success = viewModel.importConfig(context, content)
                                if (success) {
                                    Toast.makeText(context, "配置导入成功，已立即热重载生效", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "导入失败：JSON 格式不符", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (t: Throwable) {
                            Toast.makeText(context, "导入失败: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val defaultImeLabel = remember(defaultImeId, imes) {
                    imes.firstOrNull { it.id == defaultImeId }?.label ?: defaultImeId
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Modern LSPosed API 102 · v1.1.0",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { viewModel.loadData(context) }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "刷新应用列表"
                                    )
                                }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "更多设置"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        // 1. Hide Launcher Icon Toggle
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (hideLauncherIcon) "恢复桌面图标显示" else "隐藏桌面图标")
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.VisibilityOff,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showHideIconConfirmDialog = true
                                            }
                                        )

                                        // 2. Hide apps without launcher icons
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.hide_no_launcher_title))
                                            },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = hideNoLauncher,
                                                    onCheckedChange = null
                                                )
                                            },
                                            onClick = {
                                                val next = !hideNoLauncher
                                                viewModel.setHideNoLauncher(context, next)
                                            }
                                        )

                                        // 3. Aggressive recheck
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.aggressive_recheck_title))
                                            },
                                            trailingIcon = {
                                                Checkbox(
                                                    checked = aggressiveRecheck,
                                                    onCheckedChange = null
                                                )
                                            },
                                            onClick = {
                                                val next = !aggressiveRecheck
                                                viewModel.setAggressiveRecheck(context, next)
                                            }
                                        )

                                        HorizontalDivider()

                                        // 4. Export Backup
                                        DropdownMenuItem(
                                            text = { Text("导出配置 (备份)") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Upload,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                exportLauncher.launch("imeauto_backup_${System.currentTimeMillis() / 1000}.json")
                                            }
                                        )

                                        // 6. Import Restore
                                        DropdownMenuItem(
                                            text = { Text("导入配置 (还原)") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.VerticalAlignBottom,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                importLauncher.launch(arrayOf("application/json", "*/*"))
                                            }
                                        )

                                        HorizontalDivider()

                                        // 7. About Page
                                        DropdownMenuItem(
                                            text = { Text("关于 ImeAutoSwitcher") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showAboutDialog = true
                                            }
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Top status & global switches
                                item {
                                    TopStatusCard(
                                        isModuleActive = isModuleActive,
                                        globalEnabled = globalEnabled,
                                        onGlobalEnabledChange = { viewModel.setGlobalEnabled(context, it) },
                                        defaultImeLabel = defaultImeLabel,
                                        onDefaultImeClick = { viewModel.setPickingDefaultIme(true) },
                                        groupCount = groups.size,
                                        onOpenGroupManager = { showGroupManagerDialog = true }
                                    )
                                }

                                // Search input field
                                item {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.setSearchQuery(it) },
                                        placeholder = { Text(text = stringResource(R.string.search_hint)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null
                                            )
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = null
                                                    )
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                            focusedContainerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Filter chips: 全部, 用户应用, 系统应用, 已自定义, 📁 分组应用, 动态分组, 动态已选输入法
                                item {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "ALL",
                                                onClick = { viewModel.setSelectedFilter("ALL") },
                                                label = { Text(stringResource(R.string.tab_all)) },
                                                colors = FilterChipDefaults.filterChipColors()
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "USER",
                                                onClick = { viewModel.setSelectedFilter("USER") },
                                                label = { Text(stringResource(R.string.tab_user)) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "SYSTEM",
                                                onClick = { viewModel.setSelectedFilter("SYSTEM") },
                                                label = { Text(stringResource(R.string.tab_system)) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "CONFIGURED",
                                                onClick = { viewModel.setSelectedFilter("CONFIGURED") },
                                                label = { Text(stringResource(R.string.tab_configured)) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "GROUPED",
                                                onClick = { viewModel.setSelectedFilter("GROUPED") },
                                                label = { Text("📁 全部已分组") }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedFilter == "IME_APPS",
                                                onClick = { viewModel.setSelectedFilter("IME_APPS") },
                                                label = { Text("⌨️ 输入法应用") }
                                            )
                                        }

                                        // Dynamic Group Filter Chips
                                        items(groupFilters, key = { "gf_${it.groupId}" }) { gf ->
                                            val key = "GROUP:${gf.groupId}"
                                            FilterChip(
                                                selected = selectedFilter == key,
                                                onClick = {
                                                    if (selectedFilter == key) viewModel.setSelectedFilter("ALL")
                                                    else viewModel.setSelectedFilter(key)
                                                },
                                                label = { Text("📁 ${gf.name} (${gf.count})") }
                                            )
                                        }

                                        // Dynamic Selected IME Filter Chips
                                        items(usedImeFilters, key = { "uif_${it.imeId}" }) { uif ->
                                            val key = "IME:${uif.imeId}"
                                            FilterChip(
                                                selected = selectedFilter == key,
                                                onClick = {
                                                    if (selectedFilter == key) viewModel.setSelectedFilter("ALL")
                                                    else viewModel.setSelectedFilter(key)
                                                },
                                                label = { Text("⌨️ ${uif.label} (${uif.count})") }
                                            )
                                        }
                                    }
                                }

                                // List of applications
                                if (isLoading) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                } else if (apps.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "无匹配应用",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    items(apps, key = { it.packageName }) { app ->
                                        AppItemCard(
                                            app = app,
                                            allImes = imes,
                                            groups = groups,
                                            onSelectImeClick = { viewModel.selectAppForImePick(app) }
                                        )
                                    }
                                }
                            }
                        }

                        // App-Specific IME Selection Dialog
                        selectedAppForImePick?.let { targetApp ->
                            ImeSelectDialog(
                                title = "选择 ${targetApp.name} 的输入法",
                                currentSelectedId = targetApp.assignedImeId,
                                isImeApp = targetApp.isImeApp,
                                allowDefaultOption = true,
                                imes = imes,
                                groups = groups,
                                onManageGroupsClick = {
                                    viewModel.selectAppForImePick(null)
                                    showGroupManagerDialog = true
                                },
                                onSelectIme = { imeId ->
                                    viewModel.updateAppIme(context, targetApp.packageName, imeId)
                                },
                                onDismiss = { viewModel.selectAppForImePick(null) }
                            )
                        }

                        // Default Fallback IME Selection Dialog
                        if (isPickingDefaultIme) {
                            ImeSelectDialog(
                                title = stringResource(R.string.default_ime_title),
                                currentSelectedId = defaultImeId,
                                allowDefaultOption = false,
                                imes = imes,
                                onSelectIme = { imeId ->
                                    if (imeId != null) {
                                        viewModel.setDefaultIme(context, imeId)
                                    }
                                    viewModel.setPickingDefaultIme(false)
                                },
                                onDismiss = { viewModel.setPickingDefaultIme(false) }
                            )
                        }

                        // Group Manager Dialog
                        if (showGroupManagerDialog) {
                            GroupManagerDialog(
                                groups = groups,
                                allImes = imes,
                                onAddGroup = { name, imeId -> viewModel.addGroup(context, name, imeId) },
                                onUpdateGroup = { updated -> viewModel.updateGroup(context, updated) },
                                onDeleteGroup = { groupId -> viewModel.deleteGroup(context, groupId) },
                                onDismiss = { showGroupManagerDialog = false }
                            )
                        }

                        // About Dialog
                        if (showAboutDialog) {
                            AboutDialog(
                                onDismiss = { showAboutDialog = false }
                            )
                        }

                        // Hide Launcher Icon Confirmation Dialog
                        if (showHideIconConfirmDialog) {
                            val nextState = !hideLauncherIcon
                            AlertDialog(
                                onDismissRequest = { showHideIconConfirmDialog = false },
                                title = {
                                    Text(text = if (nextState) "隐藏桌面图标" else "恢复桌面图标显示")
                                },
                                text = {
                                    Text(
                                        text = if (nextState) {
                                            "隐藏桌面图标后，桌面启动器将不再显示本应用图标。\n\n后续可在 LSPosed 管理器中点击本模块条目随时重新打开本界面。\n\n是否确认隐藏？"
                                        } else {
                                            "恢复桌面图标显示后，系统桌面启动器将重新展示本应用图标。\n\n是否确认恢复？"
                                        }
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel.setHideLauncherIcon(context, nextState)
                                            showHideIconConfirmDialog = false
                                            Toast.makeText(
                                                context,
                                                if (nextState) "已隐藏桌面图标，可通过 LSPosed 打开" else "已恢复桌面图标显示",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    ) {
                                        Text(text = "确认")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showHideIconConfirmDialog = false }) {
                                        Text(text = "取消")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
