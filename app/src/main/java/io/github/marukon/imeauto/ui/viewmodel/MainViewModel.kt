package io.github.marukon.imeauto.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marukon.imeauto.data.AppListLoader
import io.github.marukon.imeauto.data.ConfigManager
import io.github.marukon.imeauto.data.model.AppItem
import io.github.marukon.imeauto.data.model.ImeGroup
import io.github.marukon.imeauto.data.model.ImeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppFilterTab {
    ALL, USER, SYSTEM, CONFIGURED, GROUPED, IME_APPS
}

data class UsedImeFilter(
    val imeId: String,
    val label: String,
    val count: Int
)

data class GroupFilterItem(
    val groupId: String,
    val name: String,
    val count: Int
)

class MainViewModel : ViewModel() {

    private val _allApps = MutableStateFlow<List<AppItem>>(emptyList())
    private val _imes = MutableStateFlow<List<ImeItem>>(emptyList())
    private val _groups = MutableStateFlow<List<ImeGroup>>(emptyList())
    private val _selectedTab = MutableStateFlow(AppFilterTab.ALL)
    private val _selectedFilter = MutableStateFlow("ALL")
    private val _hideNoLauncher = MutableStateFlow(true)
    private val _hideLauncherIcon = MutableStateFlow(false)
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
    val selectedFilter: StateFlow<String> = _selectedFilter
    val hideNoLauncher: StateFlow<Boolean> = _hideNoLauncher
    val hideLauncherIcon: StateFlow<Boolean> = _hideLauncherIcon
    val searchQuery: StateFlow<String> = _searchQuery
    val imes: StateFlow<List<ImeItem>> = _imes
    val groups: StateFlow<List<ImeGroup>> = _groups
    val isLoading: StateFlow<Boolean> = _isLoading

    val usedImeFilters: StateFlow<List<UsedImeFilter>> = combine(
        _allApps,
        _imes,
        _groups
    ) { apps, imes, groups ->
        val groupMap = groups.associate { it.id to it.targetImeId }
        val counts = mutableMapOf<String, Int>()

        for (app in apps) {
            val rule = app.assignedImeId ?: continue
            if (rule == ConfigManager.RULE_KEEP_CURRENT) continue
            val effectiveIme = if (rule.startsWith(ConfigManager.PREFIX_GROUP)) {
                val gid = rule.removePrefix(ConfigManager.PREFIX_GROUP)
                groupMap[gid]?.takeIf { it != ConfigManager.RULE_KEEP_CURRENT && it.isNotEmpty() }
            } else {
                rule
            }
            if (!effectiveIme.isNullOrEmpty()) {
                counts[effectiveIme] = (counts[effectiveIme] ?: 0) + 1
            }
        }

        counts.map { (imeId, count) ->
            val label = imes.firstOrNull { it.id == imeId }?.label ?: imeId
            UsedImeFilter(imeId, label, count)
        }.sortedByDescending { it.count }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val groupFilters: StateFlow<List<GroupFilterItem>> = combine(
        _allApps,
        _groups
    ) { apps, groups ->
        groups.map { group ->
            val targetKey = "${ConfigManager.PREFIX_GROUP}${group.id}"
            val count = apps.count { it.assignedImeId == targetKey }
            GroupFilterItem(group.id, group.name, count)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filteredApps: StateFlow<List<AppItem>> = combine(
        _allApps,
        _selectedFilter,
        _hideNoLauncher,
        _searchQuery,
        _groups
    ) { apps, filter, hideNoLauncher, query, groups ->
        val groupMap = groups.associate { it.id to it.targetImeId }

        apps.filter { app ->
            // Filter 1: Launcher icon availability
            val matchesLauncher = if (hideNoLauncher) app.isLaunchable else true

            // Filter 2: Filter selection (Category, Group, or specific IME)
            val matchesFilter = when {
                filter == "ALL" -> !app.isImeApp
                filter == "USER" -> !app.isSystem && !app.isImeApp
                filter == "SYSTEM" -> app.isSystem && !app.isImeApp
                filter == "IME_APPS" -> app.isImeApp
                filter == "CONFIGURED" -> app.assignedImeId != null
                filter == "GROUPED" -> app.assignedImeId?.startsWith(ConfigManager.PREFIX_GROUP) == true
                filter.startsWith("GROUP:") -> {
                    val gid = filter.removePrefix("GROUP:")
                    app.assignedImeId == "${ConfigManager.PREFIX_GROUP}$gid"
                }
                filter.startsWith("IME:") -> {
                    val targetIme = filter.removePrefix("IME:")
                    val rule = app.assignedImeId
                    if (rule == null) false
                    else if (rule == targetIme) true
                    else if (rule.startsWith(ConfigManager.PREFIX_GROUP)) {
                        val gid = rule.removePrefix(ConfigManager.PREFIX_GROUP)
                        groupMap[gid] == targetIme
                    } else false
                }
                else -> true
            }

            // Filter 3: Search query
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                app.name.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
            }

            matchesLauncher && matchesFilter && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun init(context: Context) {
        _globalEnabled.value = ConfigManager.isGlobalEnabled(context)
        _defaultImeId.value = ConfigManager.getDefaultIme(context)
        _hideNoLauncher.value = ConfigManager.isHideNoLauncher(context)
        _aggressiveRecheck.value = ConfigManager.isAggressiveRecheck(context)
        _hideLauncherIcon.value = ConfigManager.isLauncherIconHidden(context)
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
            _groups.value = ConfigManager.getGroups(context)
            _hideLauncherIcon.value = ConfigManager.isLauncherIconHidden(context)

            // Sync known IME packages to ConfigManager so system_server gets them
            val imePackages = AppListLoader.loadAllImePackageNames(context)
            ConfigManager.setKnownImePackages(context, imePackages)

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

    fun setHideLauncherIcon(context: Context, hide: Boolean) {
        _hideLauncherIcon.value = hide
        ConfigManager.setHideLauncherIcon(context, hide)
    }

    fun setSelectedTab(tab: AppFilterTab) {
        _selectedTab.value = tab
        _selectedFilter.value = tab.name
    }

    fun setSelectedFilter(filter: String) {
        _selectedFilter.value = filter
        when (filter) {
            "ALL" -> _selectedTab.value = AppFilterTab.ALL
            "USER" -> _selectedTab.value = AppFilterTab.USER
            "SYSTEM" -> _selectedTab.value = AppFilterTab.SYSTEM
            "CONFIGURED" -> _selectedTab.value = AppFilterTab.CONFIGURED
            "GROUPED" -> _selectedTab.value = AppFilterTab.GROUPED
            "IME_APPS" -> _selectedTab.value = AppFilterTab.IME_APPS
        }
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

        // Immediately update and re-sort so customized apps are hoisted to the top!
        val updated = _allApps.value.map { app ->
            if (app.packageName == packageName) {
                app.copy(assignedImeId = imeId)
            } else {
                app
            }
        }
        _allApps.value = updated.sortedWith(
            compareByDescending<AppItem> {
                it.assignedImeId != null && it.assignedImeId != ConfigManager.RULE_KEEP_CURRENT
            }.thenBy { it.name.lowercase() }
        )
        _selectedAppForImePick.value = null
    }

    fun addGroup(context: Context, name: String, targetImeId: String) {
        ConfigManager.addGroup(context, name, targetImeId)
        _groups.value = ConfigManager.getGroups(context)
    }

    fun updateGroup(context: Context, group: ImeGroup) {
        ConfigManager.updateGroup(context, group)
        _groups.value = ConfigManager.getGroups(context)
    }

    fun deleteGroup(context: Context, groupId: String) {
        if (_selectedFilter.value == "GROUP:$groupId") {
            setSelectedFilter("ALL")
        }
        ConfigManager.deleteGroup(context, groupId)
        _groups.value = ConfigManager.getGroups(context)

        // Re-evaluate assigned rules for affected apps
        val appRules = ConfigManager.getAppRules(context)
        _allApps.value = _allApps.value.map { app ->
            app.copy(assignedImeId = appRules[app.packageName])
        }.sortedWith(
            compareByDescending<AppItem> {
                it.assignedImeId != null && it.assignedImeId != ConfigManager.RULE_KEEP_CURRENT
            }.thenBy { it.name.lowercase() }
        )
    }

    fun exportConfig(context: Context): String {
        return ConfigManager.exportConfigJson(context)
    }

    fun importConfig(context: Context, jsonStr: String): Boolean {
        val success = ConfigManager.importConfigJson(context, jsonStr)
        if (success) {
            init(context)
        }
        return success
    }
}
