package io.github.marukon.imeauto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marukon.imeauto.R
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.ui.components.AppItemCard
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
                val aggressiveRecheck by viewModel.aggressiveRecheck.collectAsState()
                val isModuleActive by viewModel.isModuleActive.collectAsState()
                val selectedTab by viewModel.selectedTab.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val apps by viewModel.filteredApps.collectAsState()
                val imes by viewModel.imes.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                val selectedAppForImePick by viewModel.selectedAppForImePick.collectAsState()
                val isPickingDefaultIme by viewModel.isPickingDefaultIme.collectAsState()

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
                                        text = "Modern LSPosed API 102",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
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
                                        aggressiveRecheck = aggressiveRecheck,
                                        onAggressiveRecheckChange = { viewModel.setAggressiveRecheck(context, it) },
                                        hideNoLauncher = hideNoLauncher,
                                        onHideNoLauncherChange = { viewModel.setHideNoLauncher(context, it) }
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

                                // Filter chips: 全部, 用户应用, 系统应用, 已自定义
                                item {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = selectedTab == AppFilterTab.ALL,
                                                onClick = { viewModel.setSelectedTab(AppFilterTab.ALL) },
                                                label = { Text(stringResource(R.string.tab_all)) },
                                                colors = FilterChipDefaults.filterChipColors()
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedTab == AppFilterTab.USER,
                                                onClick = { viewModel.setSelectedTab(AppFilterTab.USER) },
                                                label = { Text(stringResource(R.string.tab_user)) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedTab == AppFilterTab.SYSTEM,
                                                onClick = { viewModel.setSelectedTab(AppFilterTab.SYSTEM) },
                                                label = { Text(stringResource(R.string.tab_system)) }
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = selectedTab == AppFilterTab.CONFIGURED,
                                                onClick = { viewModel.setSelectedTab(AppFilterTab.CONFIGURED) },
                                                label = { Text(stringResource(R.string.tab_configured)) }
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
                                allowDefaultOption = true,
                                imes = imes,
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
                    }
                }
            }
        }
    }
}
