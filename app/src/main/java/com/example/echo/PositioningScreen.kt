package com.example.echo

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositioningTestScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()

    val fusionEngine = remember { LocationFusionEngine(context) }
    val fusedPosition by fusionEngine.fusedPosition.collectAsState()
    val currentHeading by fusionEngine.currentHeadingFlow.collectAsState()

    val pathfinder = remember { AStarPathfinder() }

    val benchmarkLogger = remember { BenchmarkLogger() }
    var benchmarkProgress by remember { mutableFloatStateOf(0f) }

    var showClearObstaclesConfirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        scanner.startScan()
        fusionEngine.start()
        onDispose {
            scanner.stopScan()
            fusionEngine.stop()
            benchmarkLogger.cancelLogging()
        }
    }

    val locator = remember { WknnLocator() }
    val activeFingerprints = sharedViewModel.recordedPoints

    var kValue by remember { mutableFloatStateOf(4f) }
    var useWknn by remember { mutableStateOf(true) }
    var useAwknn by remember { mutableStateOf(false) } // 🌟 AWKNN 状态
    var smoothingStrategy by remember { mutableIntStateOf(2) }

    if (!sharedViewModel.isAdvancedModeEnabled) {
        kValue = 4f; useWknn = true; useAwknn = false; smoothingStrategy = 2
    }

    var groundTruthPoint by remember { mutableStateOf<Point?>(null) }

    var rawPosition by remember { mutableStateOf<Point?>(null) }
    var emaSmoothedPosition by remember { mutableStateOf<Point?>(null) }
    var currentError by remember { mutableStateOf<Double?>(null) }

    // 🌟 动态提取数据库中激活地图的拓扑数据
    val currentMap = sharedViewModel.currentMap
    val w = currentMap?.width?.toFloat() ?: 10f
    val l = currentMap?.length?.toFloat() ?: 10f
    // 防崩溃：强制限制网格间距最小为 0.1
    val s = (sharedViewModel.gridSpacing.toFloatOrNull() ?: 2f).coerceAtLeast(0.1f)

    // 解析出多边形坐标
    val mapPolygon = remember(currentMap) {
        Converters().toPointList(currentMap?.polygonBounds ?: "")
    }

    var lastUiBleHash by remember { mutableLongStateOf(0L) }

    LaunchedEffect(liveDevices, activeFingerprints, kValue, useWknn, useAwknn) {
        if (activeFingerprints.isNotEmpty() && sharedViewModel.selectedDevices.size >= 3) {
            var currentHash = 0L
            for (mac in sharedViewModel.selectedDevices.keys) {
                val device = liveDevices.find { it.macAddress == mac }
                if (device != null) currentHash += device.lastSeen
            }

            if (currentHash != lastUiBleHash && currentHash != 0L) {
                lastUiBleHash = currentHash
                val liveRssiMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }

                // 获取带详细指标的定位结果
                val locateResult = locator.locateDetailed(liveRssiMap, activeFingerprints, kValue.roundToInt(), useWknn, useAwknn)
                rawPosition = locateResult?.coordinate

                if (locateResult != null) {
                    // 将算出的绝对坐标和最小特征距离 minD1 一起喂给互补引擎，计算动态权重
                    fusionEngine.updateWknnPosition(locateResult.coordinate, locateResult.d1)

                    if (emaSmoothedPosition == null) emaSmoothedPosition = locateResult.coordinate
                    else { val alpha = 0.3; emaSmoothedPosition = Point(emaSmoothedPosition!!.x + alpha * (locateResult.coordinate.x - emaSmoothedPosition!!.x), emaSmoothedPosition!!.y + alpha * (locateResult.coordinate.y - emaSmoothedPosition!!.y)) }
                }
            }
        }
    }

    val displayPosition = when (smoothingStrategy) {
        1 -> emaSmoothedPosition
        2 -> fusedPosition ?: rawPosition
        else -> rawPosition
    }

    LaunchedEffect(displayPosition, groundTruthPoint) {
        currentError = if (displayPosition != null && groundTruthPoint != null) Math.hypot(displayPosition.x - groundTruthPoint!!.x, displayPosition.y - groundTruthPoint!!.y) else null
    }

    var globalPlannedPath by remember { mutableStateOf<List<Point>>(emptyList()) }
    var lastTarget by remember { mutableStateOf<Point?>(null) }
    var lastObstaclesHash by remember { mutableIntStateOf(0) }
    val OFF_ROUTE_THRESHOLD = 1.0

    LaunchedEffect(displayPosition, sharedViewModel.navigationTarget, sharedViewModel.obstacles) {
        val start = displayPosition
        val target = sharedViewModel.navigationTarget
        val currentObsHash = sharedViewModel.obstacles.hashCode()

        if (start == null || target == null) {
            globalPlannedPath = emptyList()
            sharedViewModel.currentPath = emptyList()
            lastTarget = target
            lastObstaclesHash = currentObsHash
            return@LaunchedEffect
        }

        var needsRecalculate = false

        if (globalPlannedPath.isEmpty() || target != lastTarget) needsRecalculate = true
        if (currentObsHash != lastObstaclesHash) needsRecalculate = true

        var closestIndex = 0

        if (!needsRecalculate) {
            var minDistance = Double.MAX_VALUE
            val searchWindow = if (globalPlannedPath.size < 2) 0
            else kotlin.math.min(globalPlannedPath.size - 1, 3)

            for (i in 0 until searchWindow) {
                val v = globalPlannedPath[i]
                val w_pt = globalPlannedPath[i + 1]

                val l2 = (w_pt.x - v.x) * (w_pt.x - v.x) + (w_pt.y - v.y) * (w_pt.y - v.y)
                val dist = if (l2 == 0.0) {
                    Math.hypot(start.x - v.x, start.y - v.y)
                } else {
                    val t = (((start.x - v.x) * (w_pt.x - v.x) + (start.y - v.y) * (w_pt.y - v.y)) / l2).coerceIn(0.0, 1.0)
                    val projX = v.x + t * (w_pt.x - v.x)
                    val projY = v.y + t * (w_pt.y - v.y)
                    Math.hypot(start.x - projX, start.y - projY)
                }

                if (dist < minDistance) {
                    minDistance = dist
                    closestIndex = i
                }
            }

            if (minDistance > OFF_ROUTE_THRESHOLD) {
                needsRecalculate = true
            }
        }

        if (needsRecalculate) {
            withContext(Dispatchers.Default) {
                // 🌟 使用全新升级的带多边形检测的 A*
                val path = pathfinder.findPath(
                    start = start,
                    target = target,
                    mapPolygon = mapPolygon, // 传入刚刚解析的多边形
                    gridResolution = s.toDouble(),
                    obstacles = sharedViewModel.obstacles.map { Point(it.x, it.y) }
                )
                globalPlannedPath = path
                sharedViewModel.currentPath = path
            }
            lastTarget = target
            lastObstaclesHash = currentObsHash
        } else {
            if (closestIndex > 0) {
                globalPlannedPath = globalPlannedPath.drop(closestIndex)
                closestIndex = 0
            }

            val visualPath = mutableListOf<Point>()
            visualPath.add(start)
            for (i in closestIndex + 1 until globalPlannedPath.size) {
                visualPath.add(globalPlannedPath[i])
            }
            sharedViewModel.currentPath = visualPath
        }
    }

    val distanceToTarget = remember(sharedViewModel.currentPath) {
        val path = sharedViewModel.currentPath
        if (path.size > 1) {
            var dist = 0.0
            for (i in 0 until path.size - 1) {
                dist += Math.hypot(path[i + 1].x - path[i].x, path[i + 1].y - path[i].y)
            }
            dist
        } else if (path.size == 1) {
            0.0 // 已到达
        } else null // 完全没有路径
    }

    var gridCoordinates by remember(w, l, s, mapPolygon) { mutableStateOf(listOf<Point>()) }
    LaunchedEffect(w, l, s, mapPolygon) {
        val pts = withContext(Dispatchers.Default) {
            val list = mutableListOf<Point>()
            var x = 0f
            while (x <= w) {
                var y = 0f
                while (y <= l) {
                    val pt = Point(x.toDouble(), y.toDouble())
                    if (mapPolygon.size < 3 || pointInOrOnPolygon(pt, mapPolygon)) list.add(pt)
                    y += s
                }
                x += s
            }
            list
        }
        gridCoordinates = pts
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600
    var lastDrawnCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val mapContent = @Composable {
        Box(
            modifier = Modifier
                .padding(all = 16.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
        ) {
            if (currentMap?.bgImageUri?.isNotEmpty() == true) {
                AsyncImage(
                    model = currentMap.bgImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.35f)
                )
            }
            if (activeFingerprints.isEmpty() || sharedViewModel.selectedDevices.size < 3) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Center)) {
                    Text("雷达待命", style = MaterialTheme.typography.headlineSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("请先锁定 3 个以上基站并在指纹页采集数据", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            } else {
                InteractiveRadarMap(
                    mapPolygon = mapPolygon, // 🌟 传给地图 UI 进行渲染
                    mapWidth = w.toDouble(),
                    mapHeight = l.toDouble(),
                    gridCoordinates = gridCoordinates,
                    recordedPoints = activeFingerprints.map { it.coordinate },
                    selectedPoint = groundTruthPoint,
                    predictedPoint = displayPosition,
                    currentHeading = currentHeading,
                    targetPoint = if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) sharedViewModel.navigationTarget else null,
                    obstacles = sharedViewModel.obstacles,
                    currentPath = if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) sharedViewModel.currentPath else emptyList(),
                    gridResolution = s.toDouble(),
                    editMode = sharedViewModel.currentInteractionState,
                    isMapFollowingMode = sharedViewModel.isMapFollowingModeEnabled,

                    onPointSelected = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.EVALUATION_MODE ||
                            sharedViewModel.currentInteractionState == InteractionState.BENCHMARK_MODE) {
                            groundTruthPoint = pt
                        }
                    },
                    onTargetSelected = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.NAVIGATION_MODE) {
                            val c = (pt.x / s).toInt()
                            val r = (pt.y / s).toInt()
                            val isObstacle = sharedViewModel.obstacles.any { it.id == "OBS_${c}_${r}" }
                            // 静默拦截墙体点击
                            if (!isObstacle) { sharedViewModel.navigationTarget = pt }
                        }
                    },
                    checkIsObstacle = { pt ->
                        val c = (pt.x / s).toInt()
                        val r = (pt.y / s).toInt()
                        sharedViewModel.obstacles.any { it.id == "OBS_${c}_${r}" }
                    },
                    onToggleObstacle = { pt ->
                        if (sharedViewModel.currentInteractionState == InteractionState.OBSTACLE_MODE) {
                            sharedViewModel.toggleObstacle(pt, s.toDouble())
                        }
                    },
                    onDragObstacle = { pt, isErasing ->
                        if (sharedViewModel.currentInteractionState == InteractionState.OBSTACLE_MODE) {
                            val c = (pt.x / s).toInt()
                            val r = (pt.y / s).toInt()
                            if (lastDrawnCell != c to r) {
                                lastDrawnCell = c to r
                                sharedViewModel.toggleObstacle(pt, s.toDouble(), isErasing)
                            }
                        }
                    }
                )

                // HUD 提示条：贴地图底边，半透明胶囊
                val hudText: String? = when (sharedViewModel.currentInteractionState) {
                    InteractionState.NAVIGATION_MODE ->
                        if (sharedViewModel.navigationTarget == null) "点击地图选定导航终点"
                        else if (sharedViewModel.currentPath.isEmpty()) "终点不可达，请重新选择"
                        else null
                    InteractionState.OBSTACLE_MODE -> "点击或拖动地图绘制/擦除墙体"
                    InteractionState.EVALUATION_MODE ->
                        if (groundTruthPoint == null) "点击地图选定基准真值" else null
                    InteractionState.BENCHMARK_MODE ->
                        if (sharedViewModel.isContinuousLogging) "正在 2Hz 记录轨迹，匀速走动..." else null
                }
                AnimatedVisibility(
                    visible = hudText != null,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { -it / 2 }),
                    exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { -it / 2 })
                ) {
                    if (hudText != null) {
                        val hudColor = when (sharedViewModel.currentInteractionState) {
                            InteractionState.NAVIGATION_MODE ->
                                if (sharedViewModel.currentPath.isEmpty() && sharedViewModel.navigationTarget != null)
                                    MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            InteractionState.BENCHMARK_MODE -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                        val hudTextColor = when (sharedViewModel.currentInteractionState) {
                            InteractionState.NAVIGATION_MODE ->
                                if (sharedViewModel.currentPath.isEmpty() && sharedViewModel.navigationTarget != null)
                                    MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                            InteractionState.BENCHMARK_MODE -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(hudColor.copy(alpha = 0.92f))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = hudText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = hudTextColor
                            )
                        }
                    }
                }
            }
        }
    }

    val controlContent = @Composable {
        when (sharedViewModel.currentInteractionState) {
            InteractionState.NAVIGATION_MODE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "对准地图正方向后校准罗盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { fusionEngine.calibrateHeading(); Toast.makeText(context, "罗盘已对齐正方向", Toast.LENGTH_SHORT).show() },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("校准罗盘", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            InteractionState.OBSTACLE_MODE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if (sharedViewModel.obstacles.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { showClearObstaclesConfirm = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空所有墙体", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("暂无墙体，开始绘制吧", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
            InteractionState.EVALUATION_MODE -> {
                if (sharedViewModel.isAdvancedModeEnabled) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("算法", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(32.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                SegmentedButton(options = listOf("KNN", "WKNN"), selectedIndex = if (useWknn) 1 else 0, onOptionSelected = { useWknn = (it == 1) })
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AWKNN", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Switch(checked = useAwknn, onCheckedChange = { useAwknn = it }, modifier = Modifier.scale(0.75f))
                        }
                        AnimatedVisibility(visible = !useAwknn) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("K=${kValue.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(32.dp))
                                Slider(value = kValue, onValueChange = { kValue = kotlin.math.round(it) }, valueRange = 1f..7f, steps = 5, modifier = Modifier.weight(1f))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("平滑", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(32.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                SegmentedButton(options = listOf("生数据", "平滑", "PDR"), selectedIndex = smoothingStrategy, onOptionSelected = { smoothingStrategy = it })
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("基准真值", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                text = if (groundTruthPoint != null) "X: ${String.format("%.2f", groundTruthPoint!!.x)}, Y: ${String.format("%.2f", groundTruthPoint!!.y)}" else "点击地图选定",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (groundTruthPoint != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            InteractionState.BENCHMARK_MODE -> {
                var benchmarkModeTab by remember { mutableIntStateOf(0) }
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedButton(options = listOf("定点", "计时", "轨迹"), selectedIndex = benchmarkModeTab, onOptionSelected = { benchmarkModeTab = it })
                    when (benchmarkModeTab) {
                        0 -> {
                            if (groundTruthPoint == null) {
                                Text("点击地图选定物理真值坐标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        "真值 X:${String.format("%.1f", groundTruthPoint!!.x)} Y:${String.format("%.1f", groundTruthPoint!!.y)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (sharedViewModel.isBenchmarking) {
                                        LinearProgressIndicator(progress = { benchmarkProgress }, modifier = Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilledTonalButton(
                                            onClick = { benchmarkLogger.cancelLogging(); sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f },
                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)
                                        ) { Text("取消", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { sharedViewModel.isBenchmarking = true; benchmarkLogger.startLogging(coroutineScope = coroutineScope, truth = groundTruthPoint!!, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, getPurePdr = { fusionEngine.purePdrPosition.value }, getGainW = { fusionEngine.currentBleWeight.value }, envLabel = sharedViewModel.currentEnvLabel, targetSamples = 50, onProgress = { benchmarkProgress = it }, onComplete = { sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f; benchmarkLogger.stopAndExport(context) }) },
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)
                                        ) { Text("采样×50", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("静置桌面，收集滤波折线图", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
                                if (sharedViewModel.isBenchmarking) {
                                    LinearProgressIndicator(progress = { benchmarkProgress }, modifier = Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { benchmarkLogger.cancelLogging(); sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f },
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)
                                    ) { Text("取消", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                                } else {
                                    FilledTonalButton(
                                        onClick = { sharedViewModel.isBenchmarking = true; benchmarkProgress = 0f; benchmarkLogger.startTimeBoundLogging(coroutineScope = coroutineScope, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, getPurePdr = { fusionEngine.purePdrPosition.value }, getGainW = { fusionEngine.currentBleWeight.value }, envLabel = sharedViewModel.currentEnvLabel, durationSeconds = 60, onProgress = { benchmarkProgress = it }, onComplete = { sharedViewModel.isBenchmarking = false; benchmarkProgress = 0f; benchmarkLogger.stopAndExport(context) }) },
                                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)
                                    ) { Text("60秒采集", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        2 -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (sharedViewModel.isContinuousLogging) "正在 2Hz 频率记录轨迹..." else "匀速走动即可录制轨迹",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sharedViewModel.isContinuousLogging) MaterialTheme.colorScheme.error else Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = { if (sharedViewModel.isContinuousLogging) { sharedViewModel.isContinuousLogging = false; benchmarkLogger.stopAndExport(context) } else { sharedViewModel.isContinuousLogging = true; benchmarkLogger.startContinuousLogging(coroutineScope = coroutineScope, liveDevicesFlow = scanner.scannedDevicesFlow, getSelectedMacs = { sharedViewModel.selectedDevices.keys }, locator = locator, activeFingerprints = activeFingerprints, kValue = kValue.roundToInt(), getFused = { fusedPosition }, getPurePdr = { fusionEngine.purePdrPosition.value }, getGainW = { fusionEngine.currentBleWeight.value }, envLabel = sharedViewModel.currentEnvLabel) } },
                                    colors = if (sharedViewModel.isContinuousLogging) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.filledTonalButtonColors(),
                                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)
                                ) { Text(if (sharedViewModel.isContinuousLogging) "停止导出" else "开始录制", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("定位引擎", fontWeight = FontWeight.Bold) },
                actions = {
                    displayPosition?.let { pos ->
                        val strategyName = listOf("RAW", "EMA", "PDR")[smoothingStrategy]

                        val extraInfo = when (sharedViewModel.currentInteractionState) {
                            InteractionState.NAVIGATION_MODE -> {
                                if (sharedViewModel.navigationTarget != null) {
                                    if (sharedViewModel.currentPath.isEmpty()) {
                                        " | 无法到达"
                                    } else {
                                        distanceToTarget?.let { " | 路程: ${String.format("%.1f", it)}m" } ?: ""
                                    }
                                } else ""
                            }
                            InteractionState.EVALUATION_MODE -> currentError?.let { " | 误差: ${String.format("%.2f", it)}m" } ?: ""
                            else -> ""
                        }

                        val statusIndicatorColor = when (sharedViewModel.currentInteractionState) {
                            InteractionState.EVALUATION_MODE -> if ((currentError ?: 0.0) < 1.5) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            InteractionState.NAVIGATION_MODE -> {
                                if (sharedViewModel.navigationTarget == null) Color.Gray
                                else if (sharedViewModel.currentPath.isEmpty()) MaterialTheme.colorScheme.error
                                else Color(0xFF4CAF50)
                            }
                            else -> Color(0xFF4CAF50)
                        }

                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusIndicatorColor))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$strategyName | X: ${String.format("%.2f", pos.x)}, Y: ${String.format("%.2f", pos.y)}$extraInfo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface), titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { innerPadding ->
        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) { mapContent() }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) { controlContent() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding)
            ) {
                // 地图框：填满剩余空间
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    mapContent()
                }

                // 模式切换栏
                val modes = listOf(
                    InteractionState.NAVIGATION_MODE,
                    InteractionState.OBSTACLE_MODE,
                    InteractionState.EVALUATION_MODE,
                    InteractionState.BENCHMARK_MODE
                )
                val modeIcons = listOf(Icons.Default.Navigation, Icons.Default.Edit, Icons.Default.Science, Icons.Default.BarChart)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                ) {
                    modes.forEachIndexed { i, mode ->
                        val isSelected = sharedViewModel.currentInteractionState == mode
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { sharedViewModel.currentInteractionState = mode }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = modeIcons[i],
                                contentDescription = mode.title,
                                modifier = Modifier.size(15.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (i < modes.size - 1) {
                            Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant).align(Alignment.CenterVertically))
                        }
                    }
                }

                // 当前模式操作内容：高度变化时带缩放动画
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(durationMillis = 300))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    controlContent()
                }
            }
        }
    }

    if (showClearObstaclesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearObstaclesConfirm = false },
            title = { Text("警告", fontWeight = FontWeight.Bold) },
            text = { Text("确定要清空地图上绘制的所有避障墙体吗？该操作不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    sharedViewModel.clearAllObstacles()
                    showClearObstaclesConfirm = false
                    Toast.makeText(context, "避障墙体已清空", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearObstaclesConfirm = false }) { Text("取消") } }
        )
    }
}
