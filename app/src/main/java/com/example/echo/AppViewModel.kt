package com.example.echo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
val THEME_PRESET_KEY = stringPreferencesKey("theme_preset")
val AUTO_SCAN_KEY = booleanPreferencesKey("auto_scan")
val ADVANCED_MODE_KEY = booleanPreferencesKey("advanced_mode")
val COLLECTION_360_KEY = booleanPreferencesKey("collection_360")
val DARK_MODE_KEY = stringPreferencesKey("dark_mode_config")
val MAP_FOLLOW_KEY = booleanPreferencesKey("map_follow")
val IGNORE_UNNAMED_KEY = booleanPreferencesKey("ignore_unnamed")
val CURRENT_MAP_ID_KEY = stringPreferencesKey("current_map_id") // 🌟 新增：记忆用户最后使用的地图

enum class ThemePreset(val title: String, val color: Color) {
    DYNAMIC("动态取色", Color.Transparent), BILIBILI("Bilibili Pink", Color(0xFFFF6699)), MIKU("Miku Green", Color(0xFF39C5BB)),
    BEYERDYNAMIC("Beyer Orange", Color(0xFFFF6600)), INTEL("Intel Blue", Color(0xFF0071C5)), NVIDIA("Nvidia Green", Color(0xFF76B900)), SAMSUNG("Samsung Blue", Color(0xFF1429A0))
}
enum class DarkModeConfig(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

enum class InteractionState(val title: String) {
    EVALUATION_MODE("误差测绘"),
    NAVIGATION_MODE("定位导航"),
    OBSTACLE_MODE("避障编辑"),
    BENCHMARK_MODE("基准测试")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedViewModel(application: Application) : AndroidViewModel(application) {

    // ================= 数据库依赖 =================
    private val mapDao = AppDatabase.getDatabase(application).mapDao()
    private val fingerprintDao = AppDatabase.getDatabase(application).fingerprintDao()
    private val obstacleDao = AppDatabase.getDatabase(application).obstacleDao()

    // ================= 🌟 阶段一核心：地图流转状态 =================
    var mapList by mutableStateOf(listOf<MapEntity>()); private set

    private val _currentActiveMapId = MutableStateFlow<String?>(null)
    val currentActiveMapId: StateFlow<String?> = _currentActiveMapId.asStateFlow()

    // 快速获取当前激活的地图实例
    val currentMap: MapEntity? get() = mapList.find { it.mapId == _currentActiveMapId.value }

    // ================= 状态流 (按当前地图动态响应) =================
    var selectedDevices by mutableStateOf(mapOf<String, BeaconDevice>())
    var recordedPoints by mutableStateOf(listOf<ReferencePoint>()); private set
    var obstacles by mutableStateOf(listOf<ObstacleEntity>()); private set

    // 导航相关
    var navigationTarget by mutableStateOf<Point?>(null)
    var currentPath by mutableStateOf<List<Point>>(emptyList())
    var currentInteractionState by mutableStateOf(InteractionState.NAVIGATION_MODE)

    // 实验面板
    var isBenchmarking by mutableStateOf(false)
    var isContinuousLogging by mutableStateOf(false)
    var currentEnvLabel by mutableStateOf("开阔静止")
    val envLabels = listOf("开阔静止", "开阔走动", "盲区静止", "盲区走动")

    // UI 配置
    var currentThemePreset by mutableStateOf(ThemePreset.DYNAMIC); private set
    var autoScan by mutableStateOf(true); private set
    var isAdvancedModeEnabled by mutableStateOf(false); private set
    var is360CollectionModeEnabled by mutableStateOf(false); private set
    var isMapFollowingModeEnabled by mutableStateOf(false); private set
    var darkModeConfig by mutableStateOf(DarkModeConfig.SYSTEM); private set
    var isIgnoreUnnamedEnabled by mutableStateOf(false); private set

    // 全局底部导航栏显隐（由 isArScanning / rawPolygonToEdit 驱动，在 MainAppScreen 计算）
    var isBottomBarVisible by mutableStateOf(true)
    var isCollectingMode by mutableStateOf(false)
    var isFabExpanded by mutableStateOf(false)
    var isSettingsDetailOpen by mutableStateOf(false)
    var gridSpacing by mutableStateOf("2")

    // AR 扫描全局状态（提升到顶层，使 ArScannerScreen 能脱离 Pager padding 真正全屏）
    var isArScanning by mutableStateOf(false)
    var rawPolygonToEdit by mutableStateOf<ScanResult?>(null)
    var pendingScanResult by mutableStateOf<ScanResult?>(null)
    // 从详情页"重新精修轮廓"进入时，携带该地图已有的底图路径，传给精修页作为初始值
    var pendingBgPath by mutableStateOf<String?>(null)

    init {
        // 1. 初始化 DataStore 偏好设置
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
                .collect { preferences ->
                    currentThemePreset = runCatching { ThemePreset.valueOf(preferences[THEME_PRESET_KEY] ?: ThemePreset.DYNAMIC.name) }.getOrDefault(ThemePreset.DYNAMIC)
                    autoScan = preferences[AUTO_SCAN_KEY] ?: true
                    isAdvancedModeEnabled = preferences[ADVANCED_MODE_KEY] ?: false
                    is360CollectionModeEnabled = preferences[COLLECTION_360_KEY] ?: false
                    isMapFollowingModeEnabled = preferences[MAP_FOLLOW_KEY] ?: false
                    darkModeConfig = runCatching { DarkModeConfig.valueOf(preferences[DARK_MODE_KEY] ?: DarkModeConfig.SYSTEM.name) }.getOrDefault(DarkModeConfig.SYSTEM)
                    isIgnoreUnnamedEnabled = preferences[IGNORE_UNNAMED_KEY] ?: false

                    // 恢复最后一次打开的地图
                    if (_currentActiveMapId.value == null) {
                        _currentActiveMapId.value = preferences[CURRENT_MAP_ID_KEY]
                    }
                }
        }

        // 2. 监听全局地图列表变化
        viewModelScope.launch(Dispatchers.IO) {
            mapDao.getAllMapsStream().collect { entities ->
                mapList = entities
                // 如果当前没有选中地图，默认选中第一个（体验兜底）
                if (_currentActiveMapId.value == null && entities.isNotEmpty()) {
                    switchActiveMap(entities.first().mapId)
                }
            }
        }

        // 3. 🌟 核心：当且仅当 ActiveMap 改变时，重新收集指纹和障碍物流！
        viewModelScope.launch(Dispatchers.IO) {
            _currentActiveMapId.filterNotNull().flatMapLatest { mapId ->
                fingerprintDao.getFingerprintsByMapStream(mapId)
            }.collect { entities ->
                recordedPoints = entities.map { it.toDomainModel() }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            _currentActiveMapId.filterNotNull().flatMapLatest { mapId ->
                obstacleDao.getObstaclesByMapStream(mapId)
            }.collect { entities ->
                obstacles = entities
            }
        }
    }

    // ================= 地图管理方法 =================

    fun switchActiveMap(mapId: String) {
        _currentActiveMapId.value = mapId
        // 清理上一个地图的导航残余状态
        navigationTarget = null
        currentPath = emptyList()
        viewModelScope.launch { getApplication<Application>().dataStore.edit { it[CURRENT_MAP_ID_KEY] = mapId } }
    }

    fun createNewMap(name: String, w: Double, l: Double, isAr: Boolean = false, polygon: List<Point> = emptyList(), bgImageUri: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val newMap = MapEntity(
                mapId = UUID.randomUUID().toString(),
                mapName = name,
                createdAt = System.currentTimeMillis(),
                width = w,
                length = l,
                polygonBounds = Converters().fromPointList(polygon),
                isArScanned = isAr,
                bgImageUri = bgImageUri
            )
            mapDao.insertMap(newMap)
            switchActiveMap(newMap.mapId)
        }
    }

    fun renameMap(mapId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mapList.find { it.mapId == mapId }?.let {
                mapDao.insertMap(it.copy(mapName = newName))
            }
        }
    }

    /**
     * 将底图文件从外部 URI 复制到 App 私有目录，更新 DB。
     * 存储路径：filesDir/maps/{mapId}/bg.jpg
     * 用于：相册选图入口 / BEV 扫描生成图入口（两者输出格式相同，只替换此方法调用侧）
     */
    fun updateMapBgImage(mapId: String, sourceUri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destDir = File(getApplication<Application>().filesDir, "maps/$mapId").also { it.mkdirs() }
                val destFile = File(destDir, "bg.jpg")
                getApplication<Application>().contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                mapList.find { it.mapId == mapId }?.let {
                    mapDao.insertMap(it.copy(bgImageUri = destFile.absolutePath))
                }
            } catch (_: Exception) {}
        }
    }

    /** 更新底图相对地图原点的偏移量（米），由精修界面双指拖动底图时触发 */
    fun updateMapBgOffset(mapId: String, offsetX: Double, offsetY: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            mapList.find { it.mapId == mapId }?.let {
                mapDao.insertMap(it.copy(bgOffsetX = offsetX, bgOffsetY = offsetY))
            }
        }
    }

    fun deleteMap(mapId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mapList.find { it.mapId == mapId }?.let {
                mapDao.deleteMap(it)
                if (_currentActiveMapId.value == mapId) {
                    _currentActiveMapId.value = null
                    recordedPoints = emptyList()
                    obstacles = emptyList()
                }
            }
        }
    }

    // ================= 指纹与障碍物方法 (注入 mapId) =================

    fun updateRecordedPoints(newList: List<ReferencePoint>) {
        val mapId = _currentActiveMapId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (newList.isEmpty()) {
                fingerprintDao.clearAllInMap(mapId)
            } else {
                val currentIds = recordedPoints.map { it.id }.toSet(); val newIds = newList.map { it.id }.toSet()
                (currentIds - newIds).forEach { id ->
                    recordedPoints.find { it.id == id }?.toEntity(mapId)?.let { fingerprintDao.deleteFingerprint(it) }
                }
                newList.forEach { fingerprintDao.insertFingerprint(it.toEntity(mapId)) }
            }
        }
    }

    fun toggleObstacle(point: Point, gridResolution: Double, forceErase: Boolean? = null) {
        val mapId = _currentActiveMapId.value ?: return
        val c = (point.x / gridResolution).toInt()
        val r = (point.y / gridResolution).toInt()
        val id = "OBS_${c}_${r}"
        val exists = obstacles.any { it.id == id }

        val shouldErase = forceErase ?: exists

        viewModelScope.launch(Dispatchers.IO) {
            if (shouldErase && exists) {
                obstacles.find { it.id == id }?.let { obstacleDao.deleteObstacle(it) }
            } else if (!shouldErase && !exists) {
                val alignedX = c * gridResolution
                val alignedY = r * gridResolution
                val newObs = ObstacleEntity(id, mapId, alignedX, alignedY)
                obstacleDao.insertObstacle(newObs)
            }
        }
    }

    fun clearAllObstacles() {
        val mapId = _currentActiveMapId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            obstacleDao.clearAllObstaclesInMap(mapId)
        }
    }

    // AR 扫描结束后批量写入自动识别的障碍物
    fun saveObstaclesForMap(mapId: String, obstacles: List<Point>, res: Double = 0.15) {
        viewModelScope.launch(Dispatchers.IO) {
            obstacleDao.clearAllObstaclesInMap(mapId)
            obstacles.forEach { p ->
                val c = (p.x / res).toInt()
                val r = (p.y / res).toInt()
                obstacleDao.insertObstacle(ObstacleEntity("AR_OBS_${c}_${r}", mapId, c * res, r * res))
            }
        }
    }

    // ================= 配置状态保存 =================
    fun changeTheme(preset: ThemePreset) { currentThemePreset = preset; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[THEME_PRESET_KEY] = preset.name } } }
    fun setAutoScanState(enabled: Boolean) { autoScan = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[AUTO_SCAN_KEY] = enabled } } }
    fun setAdvancedMode(enabled: Boolean) { isAdvancedModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[ADVANCED_MODE_KEY] = enabled } } }
    fun set360CollectionMode(enabled: Boolean) { is360CollectionModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[COLLECTION_360_KEY] = enabled } } }
    fun setMapFollowingMode(enabled: Boolean) { isMapFollowingModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[MAP_FOLLOW_KEY] = enabled } } }
    fun updateDarkModeConfig(config: DarkModeConfig) { darkModeConfig = config; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[DARK_MODE_KEY] = config.name } } }
    fun setIgnoreUnnamed(enabled: Boolean) { isIgnoreUnnamedEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[IGNORE_UNNAMED_KEY] = enabled } } }
}