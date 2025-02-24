/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import ch.deletescape.lawnchair.globalsearch.SearchProviderController
import ch.deletescape.lawnchair.groups.DrawerTabs
import ch.deletescape.lawnchair.iconpack.IconPackManager
import ch.deletescape.lawnchair.preferences.DockStyle
import ch.deletescape.lawnchair.sesame.Sesame
import ch.deletescape.lawnchair.settings.GridSize
import ch.deletescape.lawnchair.settings.GridSize2D
import ch.deletescape.lawnchair.smartspace.SmartspaceDataWidget
import ch.deletescape.lawnchair.theme.ThemeManager
import ch.deletescape.lawnchair.util.Temperature
import com.android.launcher3.*
import com.android.launcher3.util.ComponentKey
import com.android.quickstep.OverviewInteractionState
import com.google.android.apps.nexuslauncher.allapps.PredictionsFloatingHeader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

class LawnchairPreferences(val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val onChangeMap: MutableMap<String, () -> Unit> = HashMap()
    private val onChangeListeners: MutableMap<String, MutableSet<OnPreferenceChangeListener>> = HashMap()
    private var onChangeCallback: LawnchairPreferencesChangeCallback? = null
    val sharedPrefs = migratePrefs()

    private fun migratePrefs() : SharedPreferences {
        val dir = context.cacheDir.parent
        val oldFile = File(dir, "shared_prefs/" + LauncherFiles.OLD_SHARED_PREFERENCES_KEY + ".xml")
        val newFile = File(dir, "shared_prefs/" + LauncherFiles.SHARED_PREFERENCES_KEY + ".xml")
        if (oldFile.exists() && !newFile.exists()) {
            oldFile.renameTo(newFile)
            oldFile.delete()
        }
        return context.applicationContext.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
    }

    val doNothing = { }
    val recreate = { recreate() }
    private val reloadApps = { reloadApps() }
    private val reloadAll = { reloadAll() }
    val restart = { restart() }
    private val refreshGrid = { refreshGrid() }
    private val updateBlur = { updateBlur() }
    private val resetAllApps = { onChangeCallback?.resetAllApps() ?: Unit }
    private val updateSmartspace = { updateSmartspace() }
    private val reloadIcons = { reloadIcons() }
    private val reloadIconPacks = { IconPackManager.getInstance(context).packList.reloadPacks() }

    private val lawnchairConfig = LawnchairConfig.getInstance(context)

    var restoreSuccess by BooleanPref("pref_restoreSuccess", false)
    var configVersion by IntPref("config_version", if (restoreSuccess) 0 else CURRENT_VERSION)

    // Blur
    var enableBlur by BooleanPref("pref_enableBlur", lawnchairConfig.defaultEnableBlur, updateBlur)
    val enableVibrancy = true
    val blurRadius by FloatPref("pref_blurRadius", lawnchairConfig.defaultBlurStrength, updateBlur)

    // Theme
    private var iconPack by StringPref("pref_icon_pack", "", reloadIconPacks)
    val iconPacks = object : MutableListPref<String>("pref_iconPacks", reloadIconPacks,
            if (!TextUtils.isEmpty(iconPack)) listOf(iconPack) else lawnchairConfig.defaultIconPacks.asList()) {

        override fun unflattenValue(value: String) = value
    }
    var launcherTheme by StringIntPref("pref_launcherTheme", 1) { ThemeManager.getInstance(context).updateTheme() }
    val enableLegacyTreatment by BooleanPref("pref_enableLegacyTreatment", lawnchairConfig.enableLegacyTreatment, reloadIcons)
    val colorizedLegacyTreatment by BooleanPref("pref_colorizeGeneratedBackgrounds", lawnchairConfig.enableColorizedLegacyTreatment, reloadIcons)
    val enableWhiteOnlyTreatment by BooleanPref("pref_enableWhiteOnlyTreatment", lawnchairConfig.enableWhiteOnlyTreatment, reloadIcons)
    val hideStatusBar by BooleanPref("pref_hideStatusBar", lawnchairConfig.hideStatusBar, doNothing)
    val iconPackMasking by BooleanPref("pref_iconPackMasking", true, reloadIcons)
    val adaptifyIconPacks by BooleanPref("pref_generateAdaptiveForIconPack", false, reloadIcons)
    var showVoiceSearchIcon by BooleanPref("opa_enabled")
    var showAssistantIcon by BooleanPref("opa_assistant")
    val displayNotificationCount by BooleanPref("pref_displayNotificationCount", false, reloadAll)

    // Desktop
    val allowFullWidthWidgets by BooleanPref("pref_fullWidthWidgets", false, restart)
    private var gridSizeDelegate = ResettableLazy { GridSize2D(this, "numRows", "numColumns", LauncherAppState.getIDP(context), refreshGrid) }
    val gridSize by gridSizeDelegate
    val hideAppLabels by BooleanPref("pref_hideAppLabels", false, recreate)
    val showTopShadow by BooleanPref("pref_showTopShadow", true, recreate) // TODO: update the scrim instead of doing this
    val autoAddInstalled by BooleanPref("pref_add_icon_to_home", true, doNothing)
    private val homeMultilineLabel by BooleanPref("pref_homeIconLabelsInTwoLines", false, recreate)
    val homeLabelRows get() = if(homeMultilineLabel) 2 else 1
    val allowOverlap by BooleanPref("pref_allowOverlap", false, reloadAll)
    val desktopTextScale by FloatPref("pref_iconTextScaleSB", 1f, reloadAll)
    val centerWallpaper by BooleanPref("pref_centerWallpaper")

    // Smartspace
    val enableSmartspace by BooleanPref("pref_smartspace", lawnchairConfig.enableSmartspace)
    val smartspaceTime by BooleanPref("pref_smartspace_time", false, refreshGrid)
    val smartspaceTimeAbove by BooleanPref("pref_smartspace_time_above", false, refreshGrid)
    val smartspaceTime24H by BooleanPref("pref_smartspace_time_24_h", false, refreshGrid)
    val smartspaceDate by BooleanPref("pref_smartspace_date", true, refreshGrid)
    var smartspaceWidgetId by IntPref("smartspace_widget_id", -1, doNothing)
    var weatherProvider by StringPref("pref_smartspace_widget_provider",
            SmartspaceDataWidget::class.java.name, ::updateSmartspaceProvider)
    var eventProvider by StringPref("pref_smartspace_event_provider",
            SmartspaceDataWidget::class.java.name, ::updateSmartspaceProvider)
    var weatherApiKey by StringPref("pref_weatherApiKey", context.getString(R.string.default_owm_key))
    var weatherCity by StringPref("pref_weather_city", context.getString(R.string.default_city))
    val weatherUnit by StringBasedPref("pref_weather_units", Temperature.Unit.Celsius, ::updateSmartspaceProvider,
        Temperature.Companion::unitFromString, Temperature.Companion::unitToString) { }
    var usePillQsb by BooleanPref("pref_use_pill_qsb", false, recreate)

    // Dock
    val dockStyles = DockStyle.StyleManager(this, restart, resetAllApps)
    val dockColoredGoogle by BooleanPref("pref_dockColoredGoogle", false, doNothing)
    val dockSearchBarPref by BooleanPref("pref_dockSearchBar", Utilities.ATLEAST_MARSHMALLOW, restart)
    inline val dockSearchBar get() = !dockHide && dockSearchBarPref
    val dockRadius get() = dockStyles.currentStyle.radius
    val dockShadow get() = dockStyles.currentStyle.enableShadow
    val dockShowArrow get() = dockStyles.currentStyle.enableArrow
    val dockOpacity get() = dockStyles.currentStyle.opacity
    val dockShowPageIndicator by BooleanPref("pref_hotseatShowPageIndicator", true, { onChangeCallback?.updatePageIndicator() })
    val dockGradientStyle get() = dockStyles.currentStyle.enableGradient
    val dockHide get() = dockStyles.currentStyle.hide
    private val dockGridSizeDelegate = ResettableLazy { GridSize(this, "numHotseatIcons", LauncherAppState.getIDP(context), recreate) }
    val dockGridSize by dockGridSizeDelegate
    val twoRowDock by BooleanPref("pref_twoRowDock", false, recreate)
    val dockRowsCount get() = if (twoRowDock) 2 else 1
    val dockScale by FloatPref("pref_dockScale", 1f, recreate)

    // Drawer
    val hideAllAppsAppLabels by BooleanPref("pref_hideAllAppsAppLabels", false, recreate)
    val allAppsOpacity by AlphaPref("pref_allAppsOpacitySB", -1, recreate)
    val allAppsStartAlpha get() = dockStyles.currentStyle.opacity
    val allAppsEndAlpha get() = allAppsOpacity
    val allAppsSearch by BooleanPref("pref_allAppsSearch", true, recreate)
    val allAppsGlobalSearch by BooleanPref("pref_allAppsGoogleSearch", true, doNothing)
    val showAllAppsLabel by BooleanPref("pref_showAllAppsLabel", false) {
        val header = onChangeCallback?.launcher?.appsView?.floatingHeaderView as? PredictionsFloatingHeader
        header?.updateShowAllAppsLabel()
    }
    val separateWorkApps by BooleanPref("pref_separateWorkApps", true, recreate)
    val saveScrollPosition by BooleanPref("pref_keepScrollState", false, doNothing)
    private val drawerGridSizeDelegate = ResettableLazy { GridSize(this, "numColsDrawer", LauncherAppState.getIDP(context), recreate) }
    val drawerGridSize by drawerGridSizeDelegate
    private val predictionGridSizeDelegate = ResettableLazy { GridSize(this, "numPredictions", LauncherAppState.getIDP(context), recreate) }
    val predictionGridSize by predictionGridSizeDelegate
    val drawerPaddingScale by FloatPref("pref_allAppsPaddingScale", 1.0f, recreate)
    val showPredictions by BooleanPref("pref_show_predictions", true, doNothing)
    private val drawerMultilineLabel by BooleanPref("pref_iconLabelsInTwoLines", false, recreate)
    val drawerLabelRows get() = if(drawerMultilineLabel) 2 else 1
    val drawerTabs by lazy { DrawerTabs(this) }
    val showActions by BooleanPref("pref_show_suggested_actions", true, doNothing)
    val sortDrawerByColors by BooleanPref("pref_allAppsColorSorted", false, reloadAll)
    val drawerTextScale by FloatPref("pref_allAppsIconTextScale", 1f, recreate)

    // Dev
    var developerOptionsEnabled by BooleanPref("pref_showDevOptions", false, doNothing)
    private var wipOptionsKey by StringPref("pref_wipOptionsKey", "", doNothing)
    var wipOptionsEnabled
        get() = wipOptionsKey == Settings.Secure.ANDROID_ID
        set(value) {
            wipOptionsKey = if (value) Settings.Secure.ANDROID_ID else ""
        }
    val showDebugInfo by BooleanPref("pref_showDebugInfo", false, doNothing)
    val alwaysClearIconCache by BooleanPref("pref_alwaysClearIconCache", false, restart)
    val debugLegacyTreatment by BooleanPref("pref_debugLegacyTreatment", false, restart)
    val lowPerformanceMode by BooleanPref("pref_lowPerformanceMode", false, doNothing)
    val enablePhysics get() = !lowPerformanceMode
    val backupScreenshot by BooleanPref("pref_backupScreenshot", false, doNothing)
    val useScaleAnim by BooleanPref("pref_useScaleAnim", false, doNothing)
    val useWindowToIcon by BooleanPref("pref_useWindowToIcon", true, doNothing)
    val dismissTasksOnKill by BooleanPref("pref_dismissTasksOnKill", true, doNothing)
    var customFontName by StringPref("pref_customFontName", "Google Sans", doNothing)
    var forceEnableFools by BooleanPref("pref_forceEnableFools", false, restart)
    val visualizeOccupied by BooleanPref("pref_debugVisualizeOccupied")
    val scaleAdaptiveBg by BooleanPref("pref_scaleAdaptiveBg", false)
    val folderBgColored by BooleanPref("pref_folderBgColorGen", false)

    // Search
    var searchProvider by StringPref("pref_globalSearchProvider", lawnchairConfig.defaultSearchProvider) {
        SearchProviderController.getInstance(context).onSearchProviderChanged()
    }
    val dualBubbleSearch by BooleanPref("pref_bubbleSearchStyle", false, doNothing)
    // This purely exists to abuse preference change listeners, the value is never actually read.
    var sesameIconColor by IntPref("pref_sesameIconColor", -1)
    var searchBarRadius by DimensionPref("pref_searchbarRadius", -1f)

    // Quickstep
    var swipeUpToSwitchApps by BooleanPref("pref_swipe_up_to_switch_apps_enabled", true, doNothing)
    val recentsRadius by DimensionPref("pref_recents_radius", context.resources.getInteger(R.integer.task_corner_radius).toFloat(), doNothing)
    val swipeLeftToGoBack by BooleanPref("pref_swipe_left_to_go_back", false) {
        OverviewInteractionState.getInstance(context).setBackButtonAlpha(1f, true)
    }
    val recentsBlurredBackground by BooleanPref("pref_recents_blur_background", true) {
        onChangeCallback?.launcher?.background?.onEnabledChanged()
    }

    // Integrations
    var syncLookNFeelWithSesame by BooleanPref("pref_sesame_sync_icon_pack", true) {
        Sesame.setupSync(context)
    }

    // Misc
    var autoLaunchRoot by BooleanPref("internal_auto_launch_root")
    var noFools by BooleanPref("pref_noFools2019", false) { Utilities.restartLauncher(context) }
    val enableFools get() = forceEnableFools || is1stApril()
    val showFools get() = !noFools && enableFools

    private val was1stApril = is1stApril()

    fun checkFools() {
        if (was1stApril != is1stApril()) {
            restart()
        }
    }

    private fun is1stApril(): Boolean {
        val now = GregorianCalendar.getInstance()
        val date = now.get(Calendar.DAY_OF_MONTH)
        val month = now.get(Calendar.MONTH)
        return date == 1 && month == Calendar.APRIL
    }

    var hiddenAppSet by StringSetPref("hidden-app-set", Collections.emptySet(), reloadApps)
    var hiddenPredictionAppSet by StringSetPref("pref_hidden_prediction_set", Collections.emptySet(), doNothing)
    val customAppName = object : MutableMapPref<ComponentKey, String>("pref_appNameMap", reloadAll) {
        override fun flattenKey(key: ComponentKey) = key.toString()
        override fun unflattenKey(key: String) = ComponentKey(context, key)
        override fun flattenValue(value: String) = value
        override fun unflattenValue(value: String) = value
    }
    val customAppIcon = object : MutableMapPref<ComponentKey, IconPackManager.CustomIconEntry>("pref_appIconMap", reloadAll) {
        override fun flattenKey(key: ComponentKey) = key.toString()
        override fun unflattenKey(key: String) = ComponentKey(context, key)
        override fun flattenValue(value: IconPackManager.CustomIconEntry) = value.toString()
        override fun unflattenValue(value: String) = IconPackManager.CustomIconEntry.fromString(value)
    }
    val recentBackups = object : MutableListPref<Uri>(
            Utilities.getDevicePrefs(context), "pref_recentBackups") {
        override fun unflattenValue(value: String) = Uri.parse(value)
    }

    inline fun withChangeCallback(
            crossinline callback: (LawnchairPreferencesChangeCallback) -> Unit): () -> Unit {
        return { getOnChangeCallback()?.let { callback(it) } }
    }

    fun getOnChangeCallback() = onChangeCallback

    private fun recreate() {
        onChangeCallback?.recreate()
    }

    private fun reloadApps() {
        onChangeCallback?.reloadApps()
    }

    private fun reloadAll() {
        onChangeCallback?.reloadAll()
    }

    fun restart() {
        onChangeCallback?.restart()
    }

    fun refreshGrid() {
        onChangeCallback?.refreshGrid()
    }

    private fun updateBlur() {
        onChangeCallback?.updateBlur()
    }

    private fun updateSmartspaceProvider() {
        onChangeCallback?.updateSmartspaceProvider()
    }

    private fun updateSmartspace() {
        onChangeCallback?.updateSmartspace()
    }

    private fun reloadIcons() {
        onChangeCallback?.reloadIcons()
    }

    fun addOnPreferenceChangeListener(listener: OnPreferenceChangeListener, vararg keys: String) {
        keys.forEach { addOnPreferenceChangeListener(it, listener) }
    }

    fun addOnPreferenceChangeListener(key: String, listener: OnPreferenceChangeListener) {
        if (onChangeListeners[key] == null) {
            onChangeListeners[key] = HashSet()
        }
        onChangeListeners[key]?.add(listener)
        listener.onValueChanged(key, this, true)
    }

    fun removeOnPreferenceChangeListener(listener: OnPreferenceChangeListener, vararg keys: String) {
        keys.forEach { removeOnPreferenceChangeListener(it, listener) }
    }

    fun removeOnPreferenceChangeListener(key: String, listener: OnPreferenceChangeListener) {
        onChangeListeners[key]?.remove(listener)
    }

    abstract inner class MutableListPref<T>(private val prefs: SharedPreferences,
                                            private val prefKey: String,
                                            onChange: () -> Unit = doNothing,
                                            default: List<T> = emptyList()) {

        constructor(prefKey: String, onChange: () -> Unit = doNothing, default: List<T> = emptyList())
                : this(sharedPrefs, prefKey, onChange, default)

        private val valueList = ArrayList<T>()

        init {
            val arr = JSONArray(prefs.getString(prefKey, getJsonString(default)))
            (0 until arr.length()).mapTo(valueList) { unflattenValue(arr.getString(it)) }
            if (onChange != doNothing) {
                onChangeMap[prefKey] = onChange
            }
        }

        fun toList() = ArrayList<T>(valueList)

        open fun flattenValue(value: T) = value.toString()
        abstract fun unflattenValue(value: String): T

        operator fun get(position: Int): T {
            return valueList[position]
        }

        operator fun set(position: Int, value: T) {
            valueList[position] = value
            saveChanges()
        }

        fun setAll(value: List<T>) {
            if (value == valueList) return
            valueList.clear()
            valueList.addAll(value)
            saveChanges()
        }

        fun add(value: T) {
            valueList.add(value)
            saveChanges()
        }

        fun add(position: Int, value: T) {
            valueList.add(position, value)
            saveChanges()
        }

        fun remove(value: T) {
            valueList.remove(value)
            saveChanges()
        }

        fun removeAt(position: Int) {
            valueList.removeAt(position)
            saveChanges()
        }

        fun contains(value: T): Boolean {
            return valueList.contains(value)
        }

        fun replaceWith(newList: List<T>) {
            valueList.clear()
            valueList.addAll(newList)
            saveChanges()
        }

        fun getList() = valueList

        private fun saveChanges() {
            @SuppressLint("CommitPrefEdits")
            val editor = prefs.edit()
            editor.putString(prefKey, getJsonString(valueList))
            if (!bulkEditing)
                commitOrApply(editor, blockingEditing)
        }

        private fun getJsonString(list: List<T>): String {
            val arr = JSONArray()
            list.forEach { arr.put(flattenValue(it)) }
            return arr.toString()
        }
    }

    abstract inner class MutableMapPref<K, V>(private val prefKey: String, onChange: () -> Unit = doNothing) {
        private val valueMap = HashMap<K, V>()

        init {
            val obj = JSONObject(sharedPrefs.getString(prefKey, "{}"))
            obj.keys().forEach {
                valueMap[unflattenKey(it)] = unflattenValue(obj.getString(it))
            }
            if (onChange !== doNothing) {
                onChangeMap[prefKey] = onChange
            }
        }

        fun toMap() = HashMap<K, V>(valueMap)

        open fun flattenKey(key: K) = key.toString()
        abstract fun unflattenKey(key: String): K

        open fun flattenValue(value: V) = value.toString()
        abstract fun unflattenValue(value: String): V

        operator fun set(key: K, value: V?) {
            if (value != null) {
                valueMap[key] = value
            } else {
                valueMap.remove(key)
            }
            saveChanges()
        }

        private fun saveChanges() {
            val obj = JSONObject()
            valueMap.entries.forEach { obj.put(flattenKey(it.key), flattenValue(it.value)) }
            @SuppressLint("CommitPrefEdits")
            val editor = if (bulkEditing) editor!! else sharedPrefs.edit()
            editor.putString(prefKey, obj.toString())
            if (!bulkEditing)
                commitOrApply(editor, blockingEditing)
        }

        operator fun get(key: K): V? {
            return valueMap[key]
        }
    }

    open inner class StringBasedPref<T : Any>(key: String, defaultValue: T, onChange: () -> Unit = doNothing,
                                              private val fromString: (String) -> T,
                                              private val toString: (T) -> String,
                                              private val dispose: (T) -> Unit) :
            PrefDelegate<T>(key, defaultValue, onChange) {
        override fun onGetValue(): T = sharedPrefs.getString(getKey(), null)?.run(fromString) ?: defaultValue

        override fun onSetValue(value: T) {
            edit { putString(getKey(), toString(value)) }
        }

        override fun disposeOldValue(oldValue: T) {
            dispose(oldValue)
        }
    }

    open inner class StringPref(key: String, defaultValue: String = "", onChange: () -> Unit = doNothing) :
            PrefDelegate<String>(key, defaultValue, onChange) {
        override fun onGetValue(): String = sharedPrefs.getString(getKey(), defaultValue)

        override fun onSetValue(value: String) {
            edit { putString(getKey(), value) }
        }
    }

    open inner class NullableStringPref(key: String, defaultValue: String? = null, onChange: () -> Unit = doNothing) :
            PrefDelegate<String?>(key, defaultValue, onChange) {
        override fun onGetValue(): String? = sharedPrefs.getString(getKey(), defaultValue)

        override fun onSetValue(value: String?) {
            edit { putString(getKey(), value) }
        }
    }

    open inner class StringSetPref(key: String, defaultValue: Set<String>, onChange: () -> Unit = doNothing) :
            PrefDelegate<Set<String>>(key, defaultValue, onChange) {
        override fun onGetValue(): Set<String> = sharedPrefs.getStringSet(getKey(), defaultValue)

        override fun onSetValue(value: Set<String>) {
            edit { putStringSet(getKey(), value) }
        }
    }

    open inner class StringIntPref(key: String, defaultValue: Int = 0, onChange: () -> Unit = doNothing) :
            PrefDelegate<Int>(key, defaultValue, onChange) {
        override fun onGetValue(): Int = sharedPrefs.getString(getKey(), "$defaultValue").toInt()

        override fun onSetValue(value: Int) {
            edit { putString(getKey(), "$value") }
        }
    }

    open inner class IntPref(key: String, defaultValue: Int = 0, onChange: () -> Unit = doNothing) :
            PrefDelegate<Int>(key, defaultValue, onChange) {
        override fun onGetValue(): Int = sharedPrefs.getInt(getKey(), defaultValue)

        override fun onSetValue(value: Int) {
            edit { putInt(getKey(), value) }
        }
    }

    open inner class AlphaPref(key: String, defaultValue: Int = 0, onChange: () -> Unit = doNothing) :
            PrefDelegate<Int>(key, defaultValue, onChange) {
        override fun onGetValue(): Int = (sharedPrefs.getFloat(getKey(), defaultValue.toFloat() / 255) * 255).roundToInt()

        override fun onSetValue(value: Int) {
            edit { putFloat(getKey(), value.toFloat() / 255) }
        }
    }

    open inner class DimensionPref(key: String, defaultValue: Float = 0f, onChange: () -> Unit = doNothing) :
            PrefDelegate<Float>(key, defaultValue, onChange) {

        override fun onGetValue(): Float = dpToPx(sharedPrefs.getFloat(getKey(), defaultValue))

        override fun onSetValue(value: Float) {
            edit { putFloat(getKey(), pxToDp(value.toFloat())) }
        }
    }

    open inner class FloatPref(key: String, defaultValue: Float = 0f, onChange: () -> Unit = doNothing) :
            PrefDelegate<Float>(key, defaultValue, onChange) {
        override fun onGetValue(): Float = sharedPrefs.getFloat(getKey(), defaultValue)

        override fun onSetValue(value: Float) {
            edit { putFloat(getKey(), value) }
        }
    }

    open inner class BooleanPref(key: String, defaultValue: Boolean = false, onChange: () -> Unit = doNothing) :
            PrefDelegate<Boolean>(key, defaultValue, onChange) {
        override fun onGetValue(): Boolean = sharedPrefs.getBoolean(getKey(), defaultValue)

        override fun onSetValue(value: Boolean) {
            edit { putBoolean(getKey(), value) }
        }
    }

    // ----------------
    // Helper functions and class
    // ----------------

    fun getPrefKey(key: String) = "pref_$key"

    fun commitOrApply(editor: SharedPreferences.Editor, commit: Boolean) {
        if (commit) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    var blockingEditing = false
    var bulkEditing = false
    var editor: SharedPreferences.Editor? = null

    fun beginBlockingEdit() {
        blockingEditing = true
    }

    fun endBlockingEdit() {
        blockingEditing = false
    }

    @SuppressLint("CommitPrefEdits")
    fun beginBulkEdit() {
        bulkEditing = true
        editor = sharedPrefs.edit()
    }

    fun endBulkEdit() {
        bulkEditing = false
        commitOrApply(editor!!, blockingEditing)
        editor = null
    }

    inline fun blockingEdit(body: LawnchairPreferences.() -> Unit) {
        beginBlockingEdit()
        body(this)
        endBlockingEdit()
    }

    inline fun bulkEdit(body: LawnchairPreferences.() -> Unit) {
        beginBulkEdit()
        body(this)
        endBulkEdit()
    }

    abstract inner class PrefDelegate<T : Any?>(val key: String, val defaultValue: T, private val onChange: () -> Unit) {

        private var cached = false
        protected var value: T = defaultValue

        init {
            onChangeMap[key] = { onValueChanged() }
        }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (!cached) {
                value = onGetValue()
                cached = true
            }
            return value
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            discardCachedValue()
            onSetValue(value)
        }

        abstract fun onGetValue(): T

        abstract fun onSetValue(value: T)

        protected inline fun edit(body: SharedPreferences.Editor.() -> Unit) {
            @SuppressLint("CommitPrefEdits")
            val editor = if (bulkEditing) editor!! else sharedPrefs.edit()
            body(editor)
            if (!bulkEditing)
                commitOrApply(editor, blockingEditing)
        }

        internal fun getKey() = key

        private fun onValueChanged() {
            discardCachedValue()
            onChange.invoke()
        }

        private fun discardCachedValue() {
            if (cached) {
                cached = false
                value.let(::disposeOldValue)
            }
        }

        open fun disposeOldValue(oldValue: T) {

        }
    }

    inner class ResettableLazy<out T: Any>(private val create: () -> T) {

        private var initialized = false
        private var currentValue: T? = null

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (!initialized) {
                currentValue = create()
                initialized = true
            }
            return currentValue!!
        }

        fun resetValue() {
            initialized = false
            currentValue = null
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String) {
        onChangeMap[key]?.invoke()
        onChangeListeners[key]?.forEach { it.onValueChanged(key, this, false) }
    }

    fun registerCallback(callback: LawnchairPreferencesChangeCallback) {
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
        onChangeCallback = callback
    }

    fun unregisterCallback() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
        onChangeCallback = null
    }

    init {
        migrateConfig()
    }

    private fun migrateConfig() {
        if (configVersion != CURRENT_VERSION) {
            blockingEdit {
                bulkEdit {
                    // Migration codes here


                    configVersion = CURRENT_VERSION
                }
            }
        }
    }

    interface OnPreferenceChangeListener {

        fun onValueChanged(key: String, prefs: LawnchairPreferences, force: Boolean)
    }

    companion object {

        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: LawnchairPreferences? = null

        const val CURRENT_VERSION = 200

        fun getInstance(context: Context): LawnchairPreferences {
            if (INSTANCE == null) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    INSTANCE = LawnchairPreferences(context.applicationContext)
                } else {
                    try {
                        return MainThreadExecutor().submit(Callable { LawnchairPreferences.getInstance(context) }).get()
                    } catch (e: InterruptedException) {
                        throw RuntimeException(e)
                    } catch (e: ExecutionException) {
                        throw RuntimeException(e)
                    }

                }
            }
            return INSTANCE!!
        }

        fun getInstanceNoCreate(): LawnchairPreferences {
            return INSTANCE!!
        }

        fun destroyInstance() {
            INSTANCE?.apply {
                onChangeListeners.clear()
                onChangeCallback = null
                gridSizeDelegate.resetValue()
                dockGridSizeDelegate.resetValue()
                drawerGridSizeDelegate.resetValue()
                predictionGridSizeDelegate.resetValue()
            }
        }
    }
}
