package com.example.echo

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun FingerprintManagerScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val maps = sharedViewModel.mapList
    val context = LocalContext.current
    var selectedMap by remember { mutableStateOf<MapEntity?>(null) }
    var showNewMapDialog by remember { mutableStateOf(false) }
    var showManualMapDialog by remember { mutableStateOf(false) }
    var showGridSpacingDialog by remember { mutableStateOf(false) }

    // 管理模式
    var isManageMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // 退出管理模式时清空选择
    LaunchedEffect(isManageMode) { if (!isManageMode) selectedIds = emptySet() }

    // AR 扫描和精修页已提升到 MainAppScreen 顶层，FingerprintManagerScreen 只负责触发入口
    // 入口：sharedViewModel.isArScanning = true（由各按钮调用）
    if (sharedViewModel.isArScanning || sharedViewModel.rawPolygonToEdit != null) return

    // 采集界面：AnimatedVisibility 始终挂在组合树上，保证进入/退出动画都能播放
            if (sharedViewModel.isCollectingMode) BackHandler { sharedViewModel.isCollectingMode = false }
            AnimatedVisibility(
                visible = sharedViewModel.isCollectingMode,
                enter = slideInVertically(tween(350)) { it / 6 } + fadeIn(tween(350)),
                exit  = slideOutVertically(tween(300)) { it / 6 } + fadeOut(tween(250))
            ) {
                CollectionMapScreen(
                    sharedViewModel = sharedViewModel,
                    onBackClick = { sharedViewModel.isCollectingMode = false },
                    bottomPadding = bottomPadding
                )
            }

            // 普通地图列表 UI（采集界面显示时被遮盖，退出动画结束后才显示）
            if (!sharedViewModel.isCollectingMode) {
            // 管理模式拦截物理返回；FAB 展开时也拦截
            if (isManageMode) BackHandler { isManageMode = false }
            if (sharedViewModel.isFabExpanded) BackHandler { sharedViewModel.isFabExpanded = false }

            SharedTransitionLayout {
                AnimatedContent(
                    targetState = selectedMap,
                    label = "MapTransition",
                    transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }
                ) { targetMap ->
                    if (targetMap == null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            containerColor = Color.Transparent,
                            floatingActionButton = {},
                            topBar = {
                                if (isManageMode) {
                                    // ── 管理模式顶栏 ──
                                    TopAppBar(
                                        title = {
                                            Text(
                                                if (selectedIds.isEmpty()) "选择地图"
                                                else "已选 ${selectedIds.size} 个",
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        navigationIcon = {
                                            IconButton(onClick = { isManageMode = false }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出管理")
                                            }
                                        },
                                        actions = {
                                            // 全选 / 取消全选
                                            val allSelected = selectedIds.size == maps.size
                                            TextButton(onClick = {
                                                selectedIds = if (allSelected) emptySet()
                                                else maps.map { it.mapId }.toSet()
                                            }) {
                                                Text(if (allSelected) "取消全选" else "全选")
                                            }
                                            // 批量分享
                                            IconButton(
                                                onClick = {
                                                    val toShare = maps.filter { it.mapId in selectedIds }
                                                    shareMapFiles(context, toShare)
                                                },
                                                enabled = selectedIds.isNotEmpty()
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "分享")
                                            }
                                            // 批量删除
                                            IconButton(
                                                onClick = { showBatchDeleteDialog = true },
                                                enabled = selectedIds.isNotEmpty()
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "删除",
                                                    tint = if (selectedIds.isNotEmpty())
                                                        MaterialTheme.colorScheme.error
                                                    else LocalContentColor.current.copy(alpha = 0.38f)
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                    )
                                } else {
                                    // ── 普通顶栏 ──
                                    TopAppBar(
                                        title = { Text("地图资产库", fontWeight = FontWeight.Bold) },
                                        actions = {
                                            IconButton(onClick = { isManageMode = true }) {
                                                Icon(Icons.Default.Checklist, contentDescription = "管理")
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        ) { innerPadding ->
                            if (maps.isEmpty()) {
                                EmptyMapState(Modifier.fillMaxSize().padding(innerPadding)) { sharedViewModel.isFabExpanded = true }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 160.dp),
                                    contentPadding = PaddingValues(
                                        top = innerPadding.calculateTopPadding() + 8.dp,
                                        bottom = bottomPadding + 88.dp,
                                        start = 16.dp, end = 16.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(maps, key = { it.mapId }) { map ->
                                        MapCardItem(
                                            map = map,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            isManageMode = isManageMode,
                                            isSelected = map.mapId in selectedIds,
                                            onClick = {
                                                if (isManageMode) {
                                                    selectedIds = if (map.mapId in selectedIds)
                                                        selectedIds - map.mapId
                                                    else selectedIds + map.mapId
                                                } else {
                                                    selectedMap = map
                                                }
                                            },
                                            onLongClick = {
                                                if (!isManageMode) {
                                                    isManageMode = true
                                                    selectedIds = setOf(map.mapId)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // ── 背景遮罩（在选项泡之下，点击关闭）──
                        val isDark = isSystemInDarkTheme()
                        val overlayColor = if (isDark) Color(0xFF1A1A1A) else Color.White
                        AnimatedVisibility(
                            visible = sharedViewModel.isFabExpanded,
                            enter = fadeIn(tween(200)),
                            exit  = fadeOut(tween(200))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(overlayColor.copy(alpha = 0.75f))
                                    .clickable { sharedViewModel.isFabExpanded = false }
                            )
                        }

                        // ── 弹出菜单选项 ──
                        val menuItems = listOf(
                            Triple(Icons.Default.GridOn,      "采集间距")    { sharedViewModel.isFabExpanded = false; showGridSpacingDialog = true },
                            Triple(Icons.Default.Straighten,  "手动输入尺寸") { sharedViewModel.isFabExpanded = false; showManualMapDialog = true },
                            Triple(Icons.Default.ViewInAr,    "AR 实景测绘")  { sharedViewModel.isFabExpanded = false; sharedViewModel.isArScanning = true }
                        )
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = bottomPadding + 100.dp)
                        ) {
                            menuItems.forEachIndexed { index, (icon, label, action) ->
                                val enterDelay = (menuItems.size - 1 - index) * 60L
                                val exitDelay = index * 40L
                                var itemVisible by remember { mutableStateOf(false) }
                                LaunchedEffect(sharedViewModel.isFabExpanded) {
                                    if (sharedViewModel.isFabExpanded) {
                                        kotlinx.coroutines.delay(enterDelay)
                                        itemVisible = true
                                    } else {
                                        kotlinx.coroutines.delay(exitDelay)
                                        itemVisible = false
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = itemVisible,
                                    enter = fadeIn(tween(140)) + slideInHorizontally(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        ),
                                        initialOffsetX = { it }
                                    ),
                                    exit = fadeOut(tween(80)) + slideOutHorizontally(
                                        animationSpec = tween(100),
                                        targetOffsetX = { it }
                                    )
                                ) {
                                    Surface(
                                        onClick = action,
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        tonalElevation = 4.dp,
                                        modifier = Modifier
                                            .padding(bottom = 12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        ) {
                                            Icon(
                                                icon,
                                                contentDescription = label,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // ── FAB（遮罩和选项泡之上，始终可点击）──
                        if (!isManageMode) {
                            val fabRotation by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (sharedViewModel.isFabExpanded) 45f else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "fabRotation"
                            )
                            val fabScale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (sharedViewModel.isFabExpanded) 0.78f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "fabScale"
                            )
                            val fabCorner by animateDpAsState(
                                targetValue = if (sharedViewModel.isFabExpanded) 40.dp else 20.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "fabCorner"
                            )
                            FloatingActionButton(
                                onClick = { sharedViewModel.isFabExpanded = !sharedViewModel.isFabExpanded },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(fabCorner),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = bottomPadding + 16.dp)
                                    .size(79.dp)
                                    .graphicsLayer {
                                        scaleX = fabScale
                                        scaleY = fabScale
                                        // 右上角为变换原点：收缩时向右上角退，放大时从右上角向左下扩
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                                    }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "新建",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .rotate(fabRotation)
                                )
                            }
                        }
                        } // end Box
                    } else {
                        BackHandler { selectedMap = null }
                        MapDetailScreen(
                            map = targetMap,
                            sharedViewModel = sharedViewModel,
                            animatedVisibilityScope = this@AnimatedContent,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            bottomPadding = bottomPadding,
                            onBack = { selectedMap = null },
                            onReEdit = { poly ->
                                selectedMap = null
                                sharedViewModel.pendingBgPath = targetMap.bgImageUri.ifEmpty { null }
                                sharedViewModel.rawPolygonToEdit = poly
                            }
                        )
                    }
                }
            }

            if (showNewMapDialog) {
                NewMapSelectionDialog(
                    onDismiss = { showNewMapDialog = false },
                    onSelectAR = { showNewMapDialog = false; sharedViewModel.isArScanning = true },
                    onSelectManual = { showNewMapDialog = false; showManualMapDialog = true }
                )
            }
            if (showManualMapDialog) {
                ManualMapCreationDialog(
                    onDismiss = { showManualMapDialog = false },
                    onCreate = { name, w, l ->
                        // 手动建图：生成矩形四角多边形，供缩略图渲染
                        val rectPolygon = listOf(
                            Point(0.0, 0.0), Point(w, 0.0),
                            Point(w, l), Point(0.0, l)
                        )
                        sharedViewModel.createNewMap(name = name, w = w, l = l, isAr = false, polygon = rectPolygon)
                        showManualMapDialog = false
                    }
                )
            }
            // 采集间距设置弹窗
            if (showGridSpacingDialog) {
                AlertDialog(
                    onDismissRequest = { showGridSpacingDialog = false },
                    icon = { Icon(Icons.Default.GridOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title = { Text("指纹采集间距", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "相邻两个采集点之间的物理距离，建议 0.5 ~ 2.0m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(
                                value = sharedViewModel.gridSpacing,
                                onValueChange = { sharedViewModel.gridSpacing = it },
                                singleLine = true,
                                suffix = { Text("m") },
                                label = { Text("间距") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showGridSpacingDialog = false }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showGridSpacingDialog = false }) { Text("取消") }
                    }
                )
            }
            // 批量删除确认弹窗
            if (showBatchDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showBatchDeleteDialog = false },
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("删除 ${selectedIds.size} 张地图", fontWeight = FontWeight.Bold) },
                    text = { Text("所选地图及其全部指纹数据将被永久删除，无法恢复。确认继续？") },
                    confirmButton = {
                        Button(
                            onClick = {
                                selectedIds.forEach { sharedViewModel.deleteMap(it) }
                                showBatchDeleteDialog = false
                                isManageMode = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("确认删除") }
                    },
                    dismissButton = { TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") } }
                )
            }
            } // end if (!isCollectingMode)
}

// ---------------- 卡片组件 ----------------
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MapCardItem(
    map: MapEntity,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    isManageMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    with(sharedTransitionScope) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "card_${map.mapId}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    )
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 0.18f else 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val polygon = remember(map.polygonBounds) { Converters().toPointList(map.polygonBounds) }
                        // 底图（有 URI 时叠在多边形下方）
                        if (map.bgImageUri.isNotEmpty()) {
                            AsyncImage(
                                model = map.bgImageUri,
                                contentDescription = "底图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (polygon.size >= 3) {
                            val primaryColor = MaterialTheme.colorScheme.primary
                            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                val minX = polygon.minOf { it.x }.toFloat()
                                val maxX = polygon.maxOf { it.x }.toFloat()
                                val minY = polygon.minOf { it.y }.toFloat()
                                val maxY = polygon.maxOf { it.y }.toFloat()
                                val scale = minOf(size.width / (maxX - minX).coerceAtLeast(0.01f), size.height / (maxY - minY).coerceAtLeast(0.01f)) * 0.85f
                                val ox = size.width / 2f - ((minX + maxX) / 2f * scale)
                                val oy = size.height / 2f - ((minY + maxY) / 2f * scale)
                                fun toPx(p: Point) = Offset(ox + p.x.toFloat() * scale, oy + p.y.toFloat() * scale)
                                val path = Path().apply {
                                    moveTo(toPx(polygon.first()).x, toPx(polygon.first()).y)
                                    polygon.drop(1).forEach { lineTo(toPx(it).x, toPx(it).y) }
                                    close()
                                }
                                drawPath(path, primaryColor.copy(alpha = 0.15f))
                                drawPath(path, primaryColor.copy(alpha = if (isSelected) 0.9f else 0.6f), style = Stroke(width = 2.dp.toPx()))
                            }
                        } else {
                            Icon(
                                imageVector = if (map.isArScanned) Icons.Default.ViewInAr else Icons.Default.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = map.mapName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "${String.format("%.1f", map.width)}m × ${String.format("%.1f", map.length)}m", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "创建于 ${formatter.format(Date(map.createdAt))}", style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.8f))
                    }
                }
            }
            // 管理模式勾选标记
            if (isManageMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(24.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ---------------- 全屏详情组件 ----------------
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapDetailScreen(
    map: MapEntity,
    sharedViewModel: SharedViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onReEdit: ((List<Point>) -> Unit)? = null
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isActive = sharedViewModel.currentActiveMapId.collectAsState().value == map.mapId
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 检测多边形是否全部为直角（容差 ±10°）
    val polygon = remember(map.polygonBounds) { Converters().toPointList(map.polygonBounds) }
    val isOrthogonal = remember(polygon) {
        if (polygon.size < 3) return@remember true
        polygon.indices.all { i ->
            val a = polygon[(i - 1 + polygon.size) % polygon.size]
            val b = polygon[i]
            val c = polygon[(i + 1) % polygon.size]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val len1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y)
            val len2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y)
            if (len1 < 1e-6 || len2 < 1e-6) return@all true
            val cosA = (v1x * v2x + v1y * v2y) / (len1 * len2)
            val angleDeg = Math.toDegrees(kotlin.math.acos(cosA.coerceIn(-1.0, 1.0)))
            // 接受 90°（直角）或 180°（共线顶点），容差 ±10°
            kotlin.math.abs(angleDeg - 90.0) <= 10.0 || angleDeg >= 170.0
        }
    }

    BackHandler(onBack = onBack)

    with(sharedTransitionScope) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "card_${map.mapId}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                ),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("地图详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { shareMapFiles(context, listOf(map)) }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // 底图层
                    if (map.bgImageUri.isNotEmpty()) {
                        AsyncImage(
                            model = map.bgImageUri,
                            contentDescription = "底图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(alpha = 0.35f)
                        )
                    }
                    // 虚线网格层
                    GridCanvas(polygon = polygon, modifier = Modifier.fillMaxSize())
                    // 轮廓线层
                    if (polygon.size >= 3) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                            val minX = polygon.minOf { it.x }.toFloat()
                            val maxX = polygon.maxOf { it.x }.toFloat()
                            val minY = polygon.minOf { it.y }.toFloat()
                            val maxY = polygon.maxOf { it.y }.toFloat()
                            val scale = minOf(size.width / (maxX - minX).coerceAtLeast(0.01f), size.height / (maxY - minY).coerceAtLeast(0.01f)) * 0.85f
                            val ox = size.width / 2f - ((minX + maxX) / 2f * scale)
                            val oy = size.height / 2f - ((minY + maxY) / 2f * scale)
                            fun toPx(p: Point) = Offset(ox + p.x.toFloat() * scale, oy + p.y.toFloat() * scale)
                            val path = Path().apply {
                                moveTo(toPx(polygon.first()).x, toPx(polygon.first()).y)
                                polygon.drop(1).forEach { lineTo(toPx(it).x, toPx(it).y) }
                                close()
                            }
                            drawPath(path, primaryColor.copy(alpha = 0.12f))
                            drawPath(path, primaryColor.copy(alpha = 0.8f), style = Stroke(width = 3.dp.toPx()))
                            polygon.forEach { drawCircle(primaryColor, radius = 4.dp.toPx(), center = toPx(it)) }
                        }
                    } else {
                        Text("暂无轮廓数据", color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(map.mapName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("生成方式: ${if (map.isArScanned) "AR 激光雷达扫描" else "手动尺寸构建"}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    Text("尺寸: ${String.format("%.1f", map.width)}m × ${String.format("%.1f", map.length)}m", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("建图时间: ${formatter.format(Date(map.createdAt))}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

                // 非直角多边形警告
                if (!isOrthogonal) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "地图轮廓存在非直角边，无法用于雷达采集。请先进入精修页执行「一键直角化」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 重新精修按钮（仅 AR 扫描地图且有多边形数据时显示）
                if (onReEdit != null && polygon.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onReEdit(polygon) },
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(bottom = 8.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重新精修轮廓", fontWeight = FontWeight.Bold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding + 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            sharedViewModel.switchActiveMap(map.mapId)
                            Toast.makeText(context, "已切换导航地图至：${map.mapName}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Navigation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isActive) "当前使用中" else "激活地图", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            if (!isActive) sharedViewModel.switchActiveMap(map.mapId)
                            sharedViewModel.isCollectingMode = true
                        },
                        enabled = isOrthogonal,
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("指纹采集", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameMapDialog(
            currentName = map.mapName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                sharedViewModel.renameMap(map.mapId, newName)
                showRenameDialog = false
            }
        )
    }

    // 删除确认弹窗
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除地图", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除「${map.mapName}」吗？该地图的所有指纹数据将被永久删除，此操作不可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        sharedViewModel.deleteMap(map.mapId)
                        showDeleteDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun RenameMapDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }
    val isValid = nameInput.isNotBlank() && nameInput != currentName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名地图", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("地图名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (isValid) onConfirm(nameInput.trim()) })
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameInput.trim()) },
                enabled = isValid
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- 分享/导出工具函数 ----------------
/**
 * 将地图列表序列化为 JSON 文件，通过系统分享 Sheet 发送。
 * 格式：JSON 数组，每项包含地图基本信息，兼容后续导入解析。
 */
fun shareMapFiles(context: Context, maps: List<MapEntity>) {
    if (maps.isEmpty()) return
    val sb = StringBuilder("[\n")
    maps.forEachIndexed { index, map ->
        sb.append("""  {
    "mapId": "${map.mapId}",
    "mapName": "${map.mapName.replace("\"", "\\\"")}",
    "createdAt": ${map.createdAt},
    "width": ${map.width},
    "length": ${map.length},
    "isArScanned": ${map.isArScanned},
    "polygonBounds": "${map.polygonBounds.replace("\"", "\\\"")}"
  }""")
        if (index < maps.size - 1) sb.append(",")
        sb.append("\n")
    }
    sb.append("]")

    val fileName = if (maps.size == 1) "${maps[0].mapName}.echo.json"
                   else "echo_maps_export_${System.currentTimeMillis()}.json"
    val file = File(context.cacheDir, fileName)
    file.writeText(sb.toString())

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Echo 地图数据导出")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享地图文件"))
}

// ---------------- 空状态与对话框 ----------------
@Composable
fun EmptyMapState(modifier: Modifier, onAddClick: () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("你的空间资产库空空如也", style = MaterialTheme.typography.titleMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("使用 AR 扫描房间，或手动建立平面图", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

@Composable
fun NewMapSelectionDialog(onDismiss: () -> Unit, onSelectAR: () -> Unit, onSelectManual: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建空间地图", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(onClick = onSelectAR, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column { Text("AR 实景测绘 (推荐)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer); Text("开启相机扫描房间角落生成真实多边形边界。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)) }
                    }
                }
                Card(onClick = onSelectManual, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column { Text("手动输入尺寸", fontWeight = FontWeight.Bold); Text("输入房间长宽，建立矩形平面图。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


// 判断点是否在多边形内或边上（射线法 + 线段检测，修复边界点丢失问题）
fun pointInOrOnPolygon(pt: Point, polygon: List<Point>): Boolean {
    val eps = 1e-9
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val xi = polygon[i].x; val yi = polygon[i].y
        val xj = polygon[j].x; val yj = polygon[j].y
        // 检测点是否在当前线段上
        val minX = minOf(xi, xj); val maxX = maxOf(xi, xj)
        val minY = minOf(yi, yj); val maxY = maxOf(yi, yj)
        if (pt.x in minX - eps..maxX + eps && pt.y in minY - eps..maxY + eps) {
            val cross = (xj - xi) * (pt.y - yi) - (yj - yi) * (pt.x - xi)
            if (kotlin.math.abs(cross) < eps) return true
        }
        // 射线法
        if ((yi > pt.y) != (yj > pt.y) &&
            pt.x < (xj - xi) * (pt.y - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

@Composable
fun ManualMapCreationDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, w: Double, l: Double) -> Unit
) {
    var mapName by remember { mutableStateOf("") }
    var widthInput by remember { mutableStateOf("") }
    var lengthInput by remember { mutableStateOf("") }

    val width = widthInput.toDoubleOrNull()
    val length = lengthInput.toDoubleOrNull()
    val isValid = width != null && width > 0 && length != null && length > 0 && mapName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动建图", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = mapName,
                    onValueChange = { mapName = it },
                    label = { Text("地图名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = widthInput,
                        onValueChange = { widthInput = it },
                        label = { Text("宽度 (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        isError = widthInput.isNotEmpty() && (width == null || width <= 0)
                    )
                    OutlinedTextField(
                        value = lengthInput,
                        onValueChange = { lengthInput = it },
                        label = { Text("长度 (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (isValid) onCreate(mapName.trim(), width!!, length!!) }),
                        isError = lengthInput.isNotEmpty() && (length == null || length <= 0)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(mapName.trim(), width!!, length!!) },
                enabled = isValid
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========================================================================================================
// 🌟 原版雷达采集页面 (完美兼容新架构，从 sharedViewModel.currentMap 读取地图尺寸)
// ========================================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionMapScreen(sharedViewModel: SharedViewModel, onBackClick: () -> Unit, bottomPadding: Dp) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    // 🌟 动态读取当前激活的地图长宽
    val currentMap = sharedViewModel.currentMap
    val w = currentMap?.width?.toFloat() ?: 10f
    val l = currentMap?.length?.toFloat() ?: 10f
    val s = (sharedViewModel.gridSpacing.toFloatOrNull() ?: 1f).coerceAtLeast(0.1f)
    val mapPolygon = remember(currentMap?.polygonBounds) {
        currentMap?.polygonBounds?.let { Converters().toPointList(it) } ?: emptyList()
    }

    // 异步计算格点，避免大地图首次进入时主线程卡顿
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
    var selectedPoint by remember { mutableStateOf<Point?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var collectionProgress by remember { mutableFloatStateOf(0f) }
    var accumulatedYaw by remember { mutableFloatStateOf(0f) }
    var lastYaw by remember { mutableStateOf<Float?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var lastTapPoint by remember { mutableStateOf<Point?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) { scanner.startScan(); onDispose { scanner.stopScan() } }
    DisposableEffect(isRecording, sharedViewModel.is360CollectionModeEnabled) {
        var listener: SensorEventListener? = null
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && rotationSensor != null) {
            accumulatedYaw = 0f; lastYaw = null; collectionProgress = 0f
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val rotationMatrix = FloatArray(9); SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values); val orientation = FloatArray(3); SensorManager.getOrientation(rotationMatrix, orientation)
                        val currentYaw = orientation[0]
                        if (lastYaw != null) {
                            var delta = currentYaw - lastYaw!!
                            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat(); if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                            accumulatedYaw += delta; collectionProgress = (abs(accumulatedYaw) / (2 * Math.PI).toFloat()).coerceIn(0f, 1f)
                        }
                        lastYaw = currentYaw
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { listener?.let { sensorManager.unregisterListener(it) } }
    }

    LaunchedEffect(collectionProgress) {
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && collectionProgress >= 1f) {
            val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
            val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
            sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
            isRecording = false; collectionProgress = 0f
        }
    }

    val configuration = LocalConfiguration.current; val isWideScreen = configuration.screenWidthDp > 600
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val mapContent = @Composable {
        Box(modifier = Modifier.fillMaxSize().padding(all = 16.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))) {
            if (currentMap?.bgImageUri?.isNotEmpty() == true) {
                AsyncImage(
                    model = currentMap.bgImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.35f)
                )
            }
            InteractiveRadarMap(
                mapPolygon = mapPolygon,
                mapWidth = w.toDouble(),
                mapHeight = l.toDouble(),
                gridCoordinates = gridCoordinates,
                recordedPoints = sharedViewModel.recordedPoints.map { it.coordinate },
                selectedPoint = selectedPoint, predictedPoint = null,
                onPointSelected = { pt ->
                    val currentTime = System.currentTimeMillis()
                    if (pt == lastTapPoint && (currentTime - lastTapTime) < 300) { if (sharedViewModel.recordedPoints.any { it.coordinate == pt }) { sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != pt }); selectedPoint = null } } else { selectedPoint = pt }
                    lastTapPoint = pt; lastTapTime = currentTime
                }
            )
        }
    }

    val controlContent = @Composable {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前选定坐标", style = MaterialTheme.typography.labelMedium, color = Color.Gray); Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (selectedPoint != null) "X: ${selectedPoint!!.x}, Y: ${selectedPoint!!.y}" else "请在地图上选点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (selectedPoint != null) MaterialTheme.colorScheme.primary else Color.Gray); Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (sharedViewModel.selectedDevices.isEmpty()) "❌ 未锁定基站" else if (sharedViewModel.is360CollectionModeEnabled) "↻ 360° 旋转采集模式" else "⚡ 极速单点模式", style = MaterialTheme.typography.labelMedium, color = if (sharedViewModel.selectedDevices.isEmpty()) MaterialTheme.colorScheme.error else Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(4.dp)); Text("双击已采集点可直接删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                        if (isRecording && sharedViewModel.is360CollectionModeEnabled) {
                            CircularProgressIndicator(progress = { 1f }, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            CircularProgressIndicator(progress = { collectionProgress }, color = MaterialTheme.colorScheme.primary, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            Text(text = "${(collectionProgress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        } else {
                            val canRecord = selectedPoint != null && sharedViewModel.selectedDevices.isNotEmpty()
                            FilledIconButton(onClick = {
                                if (canRecord) {
                                    isRecording = true
                                    if (!sharedViewModel.is360CollectionModeEnabled) {
                                        val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
                                        val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
                                        sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
                                        isRecording = false
                                    }
                                }
                            }, enabled = canRecord, modifier = Modifier.fillMaxSize(), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Icon(imageVector = if (sharedViewModel.is360CollectionModeEnabled) Icons.Default.Refresh else Icons.Default.Add, contentDescription = "采集", modifier = Modifier.size(44.dp), tint = if (canRecord) MaterialTheme.colorScheme.onPrimary else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("采集雷达: ${currentMap?.mapName ?: "未知"}", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    if (sharedViewModel.recordedPoints.isNotEmpty()) {
                        FilledTonalButton(onClick = { showClearAllDialog = true }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.padding(end = 16.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("清空本图", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface))
            )
        }
    ) { innerPadding ->
        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) { Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) { mapContent() }; Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) { controlContent() } }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                // 地图区：weight(1f) 占满剩余空间，内部按比例居中显示
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { mapContent() }
                // 控制栏：固定高度不压缩
                Column { controlContent(); Spacer(modifier = Modifier.height(bottomPadding + 8.dp)) }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(onDismissRequest = { showClearAllDialog = false }, title = { Text("清空本图点位") }, text = { Text("确定要清空该地图上的所有指纹点位吗？该操作不可恢复。") }, confirmButton = { Button(onClick = { sharedViewModel.updateRecordedPoints(emptyList()); showClearAllDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("全部清空") } }, dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("取消") } })
    }
}