package io.github.marukon.imeauto.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marukon.imeauto.data.AppListLoader
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.data.model.AppItem
import io.github.marukon.imeauto.data.model.ImeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppFilterTab {
    ALL, USER, SYSTEM, CONFIGURED
}

class MainViewModel : ViewModel() {

    private val _allApps = MutableStateFlow<List<AppItem>>(emptyList())
    private val _imes = MutableStateFlow<List<ImeItem>>(emptyList())
    private val _selectedTab = MutableStateFlow(AppFilterTab.ALL)
    private val _hideNoLauncher = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)

    private val _globalEnabled = MutableStateFlow(true)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled

    private val _defaultImeId = MutableStateFlow("")
    val defaultImeId: StateFlow<String> = _defaultImeId

    private val _aggressiveRecheck = MutableStateFlow(true)
    val aggressiveRecheck: StateFlow<Boolean> = _aggressiveRecheck

    private val _isModuleActive = MutableStateFlow(false)
    val isModuleActive: StateFlow<Boolean> = _isModuleActive

    private val _selectedAppForImePick = MutableStateFlow<AppItem?>(null)
    val selectedAppForImePick: StateFlow<AppItem?> = _selectedAppForImePick

    private val _isPickingDefaultIme = MutableStateFlow(false)
    val isPickingDefaultIme: StateFlow<Boolean> = _isPickingDefaultIme

    val selectedTab: StateFlow<AppFilterTab> = _selectedTab
    val hideNoLauncher: StateFlow<Boolean> = _hideNoLauncher
    val searchQuery: StateFlow<String> = _searchQuery
    val imes: StateFlow<List<ImeItem>> = _imes
    val isLoading: StateFlow<Boolean> = _isLoading

    val filteredApps: StateFlow<List<AppItem>> = combine(
        _allApps,
        _selectedTab,
        _hideNoLauncher,
        _searchQuery
    ) { apps, tab, hideNoLauncher, query ->
        apps.filter { app ->
            // Filter 1: Launcher icon availability
            val matchesLauncher = if (hideNoLauncher) app.isLaunchable else true

            // Filter 2: Tab selection
            val matchesTab = when (tab) {
                AppFilterTab.ALL -> true
                AppFilterTab.USER -> !app.isSystem
                AppFilterTab.SYSTEM -> app.isSystem
                AppFilterTab.CONFIGURED -> app.assignedImeId != null
            }

            // Filter 3: Search query
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                app.name.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
            }

            matchesLauncher && matchesTab && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun init(context: Context) {
        _globalEnabled.value = ConfigManager.isGlobalEnabled(context)
        _defaultImeId.value = ConfigManager.getDefaultIme(context)
        _hideNoLauncher.value = ConfigManager.isHideNoLauncher(context)
        _aggressiveRecheck.value = ConfigManager.isAggressiveRecheck(context)
        _isModuleActive.value = ConfigManager.isModuleActive(context)

        loadData(context)
        ConfigManager.notifyHook(context)
    }

    fun checkModuleStatus(context: Context) {
        _isModuleActive.value = ConfigManager.isModuleActive(context)
    }

    fun loadData(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            val imes = AppListLoader.loadInstalledImes(context)
            _imes.value = imes

            // If default IME not yet set in config, automatically default to system active IME
            if (_defaultImeId.value.isEmpty()) {
                val currentSystemDefault = imes.firstOrNull { it.isDefault }?.id
                if (currentSystemDefault != null) {
                    _defaultImeId.value = currentSystemDefault
                    ConfigManager.setDefaultIme(context, currentSystemDefault)
                }
            }

            val appRules = ConfigManager.getAppRules(context)
            val apps = AppListLoader.loadInstalledApps(context, appRules)
            _allApps.value = apps
            _isLoading.value = false
        }
    }

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        _globalEnabled.value = enabled
        ConfigManager.setGlobalEnabled(context, enabled)
    }

    fun setDefaultIme(context: Context, imeId: String) {
        _defaultImeId.value = imeId
        ConfigManager.setDefaultIme(context, imeId)
    }

    fun setHideNoLauncher(context: Context, hide: Boolean) {
        _hideNoLauncher.value = hide
        ConfigManager.setHideNoLauncher(context, hide)
    }

    fun setAggressiveRecheck(context: Context, enabled: Boolean) {
        _aggressiveRecheck.value = enabled
        ConfigManager.setAggressiveRecheck(context, enabled)
    }

    fun setSelectedTab(tab: AppFilterTab) {
        _selectedTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectAppForImePick(app: AppItem?) {
        _selectedAppForImePick.value = app
    }

    fun setPickingDefaultIme(picking: Boolean) {
        _isPickingDefaultIme.value = picking
    }

    fun updateAppIme(context: Context, packageName: String, imeId: String?) {
        ConfigManager.setAppRule(context, packageName, imeId)
        _allApps.value = _allApps.value.map { app ->
            if (app.packageName == packageName) {
                app.copy(assignedImeId = imeId)
            } else {
                app
            }
        }
        _selectedAppForImePick.value = null
    }
}
