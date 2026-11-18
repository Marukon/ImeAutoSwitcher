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
    private val _selectedFilter = MutableStateFlow("ALL")
    private val _hideNoLauncher = MutableStateFlow(true)
    private val _hideLauncherIcon = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)

    private val _globalEnabled = MutableStateFlow(true)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled

    private val _defaultImeId = MutableStateFlow("")
    val defaultImeId: StateFlow<String> = _defaultImeId

    private val _aggressiveRecheck = MutableStateFlow(false)
    val aggressiveRecheck: StateFlow<Boolean> = _aggressiveRecheck

    private val _isModuleActive = MutableStateFlow(false)
    val isModuleActive: StateFlow<Boolean> = _isModuleActive

    private val _selectedAppForImePick = MutableStateFlow<AppItem?>(null)
    val selectedAppForImePick: StateFlow<AppItem?> = _selectedAppForImePick

    private val _isPickingDefaultIme = MutableStateFlow(false)
    val isPickingDefaultIme: StateFlow<Boolean> = _isPickingDefaultIme

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

    private fun isEffectiveCustomRule(rule: String?, groupMap: Map<String, String>): Boolean {
        if (rule.isNullOrEmpty() || rule == ConfigManager.RULE_KEEP_CURRENT) return false
        if (rule.startsWith(ConfigManager.PREFIX_GROUP)) {
            val gid = rule.removePrefix(ConfigManager.PREFIX_GROUP)
            val target = groupMap[gid]
            return !target.isNullOrEmpty() && target != ConfigManager.RULE_KEEP_CURRENT
        }
        return true
    }

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
                filter == "CONFIGURED" -> isEffectiveCustomRule(app.assignedImeId, groupMap)
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

        val appContext = context.applicationContext ?: context
        viewModelScope.launch {
            ConfigManager.frameworkConnectedFlow.collect { connected ->
                if (connected || !_isModuleActive.value) {
                    _isModuleActive.value = ConfigManager.isModuleActive(appContext)
                }
            }
        }

        loadData(context)
    }

    fun checkModuleStatus(context: Context) {
        _isModuleActive.value = ConfigManager.isModuleActive(context)
    }

    fun loadData(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            _isModuleActive.value = ConfigManager.isModuleActive(context)
            val imes = AppListLoader.loadInstalledImes(context)
            _imes.value = imes
            val groups = ConfigManager.getGroups(context)
            _groups.value = groups
            _hideLauncherIcon.value = ConfigManager.isLauncherIconHidden(context)

            // Sync known IME packages to ConfigManager so system_server gets them
            val imePackages = AppListLoader.loadAllImePackageNames(context)
            ConfigManager.setKnownImePackages(context, imePackages, syncNow = false)

            // If a fixed default IME was uninstalled, reset to follow system default ("")
            val configuredDefault = _defaultImeId.value
            if (configuredDefault.isNotEmpty() && imes.none { it.id == configuredDefault }) {
                _defaultImeId.value = ""
                ConfigManager.setDefaultIme(context, "")
            } else {
                ConfigManager.notifyHook(context)
            }

            val appRules = ConfigManager.getAppRules(context)
            val apps = AppListLoader.loadInstalledApps(context, appRules)
            _allApps.value = sortAppList(apps, groups)
            validateSelectedFilter()
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

    fun setSelectedFilter(filter: String) {
        _selectedFilter.value = filter
    }

    private fun validateSelectedFilter() {
        val cur = _selectedFilter.value
        if (cur.startsWith("GROUP:")) {
            val gid = cur.removePrefix("GROUP:")
            if (_groups.value.none { it.id == gid }) {
                _selectedFilter.value = "ALL"
            }
        } else if (cur.startsWith("IME:")) {
            val targetIme = cur.removePrefix("IME:")
            val groupMap = _groups.value.associate { it.id to it.targetImeId }
            val stillUsed = _allApps.value.any { app ->
                val rule = app.assignedImeId ?: return@any false
                if (rule == targetIme) true
                else if (rule.startsWith(ConfigManager.PREFIX_GROUP)) {
                    groupMap[rule.removePrefix(ConfigManager.PREFIX_GROUP)] == targetIme
                } else false
            }
            if (!stillUsed) {
                _selectedFilter.value = "ALL"
            }
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

    private val appListCollator = java.text.Collator.getInstance(java.util.Locale.getDefault()).apply {
        strength = java.text.Collator.PRIMARY
    }

    private fun sortAppList(list: List<AppItem>, groups: List<ImeGroup> = _groups.value): List<AppItem> {
        val groupMap = groups.associate { it.id to it.targetImeId }
        return list.sortedWith(
            compareByDescending<AppItem> {
                isEffectiveCustomRule(it.assignedImeId, groupMap)
            }.thenComparator { a, b ->
                val comp = appListCollator.compare(a.name, b.name)
                if (comp != 0) comp else a.packageName.compareTo(b.packageName)
            }
        )
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
        _allApps.value = sortAppList(updated)
        validateSelectedFilter()
        _selectedAppForImePick.value = null
    }

    fun addGroup(context: Context, name: String, targetImeId: String) {
        ConfigManager.addGroup(context, name, targetImeId)
        _groups.value = ConfigManager.getGroups(context)
    }

    fun updateGroup(context: Context, group: ImeGroup) {
        ConfigManager.updateGroup(context, group)
        val updatedGroups = ConfigManager.getGroups(context)
        _groups.value = updatedGroups
        _allApps.value = sortAppList(_allApps.value, updatedGroups)
        validateSelectedFilter()
    }

    fun deleteGroup(context: Context, groupId: String) {
        if (_selectedFilter.value == "GROUP:$groupId") {
            setSelectedFilter("ALL")
        }
        ConfigManager.deleteGroup(context, groupId)
        val updatedGroups = ConfigManager.getGroups(context)
        _groups.value = updatedGroups

        // Re-evaluate assigned rules for affected apps
        val appRules = ConfigManager.getAppRules(context)
        val updated = _allApps.value.map { app ->
            app.copy(assignedImeId = appRules[app.packageName])
        }
        _allApps.value = sortAppList(updated, updatedGroups)
        validateSelectedFilter()
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
