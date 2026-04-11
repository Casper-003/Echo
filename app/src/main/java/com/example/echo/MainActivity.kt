package com.example.echo

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor
import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.echo.ui.theme.MaterialColorSchemeGenerator

@SuppressLint("InlinedApi")
class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent { MainAppRouter() }
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        )
    }
}

@SuppressLint("NewApi")
@Composable
fun MainAppRouter() {
    val sharedViewModel: SharedViewModel = viewModel()
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (sharedViewModel.darkModeConfig) {
        DarkModeConfig.SYSTEM -> isSystemDark
        DarkModeConfig.LIGHT -> false
        DarkModeConfig.DARK -> true
    }
    val preset = sharedViewModel.currentThemePreset
    val isDynamic = preset == ThemePreset.DYNAMIC && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val colorScheme = if (isDynamic) {
        if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // 当 DYNAMIC 在 Android < 12 时回退到 Miku Green
        val seed = if (preset == ThemePreset.DYNAMIC) ThemePreset.MIKU.color else preset.color
        if (isDarkTheme) MaterialColorSchemeGenerator.darkScheme(seed)
        else             MaterialColorSchemeGenerator.lightScheme(seed)
    }

    // 根据当前主题动态更新状态栏图标颜色：深色模式用白色图标，浅色模式用深色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme) { Surface(color = MaterialTheme.colorScheme.background) { MainAppScreen(sharedViewModel) } }
}


@Composable
fun MainAppScreen(sharedViewModel: SharedViewModel) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

    // 非首页时，返回键跳回地图页（index=1）；地图页时正常退出
    // 设置页子项展开时不拦截，让 SettingsScreen 内部的 BackHandler 优先处理
    val currentPage = pagerState.currentPage
    if (currentPage != 1 && !sharedViewModel.isSettingsDetailOpen) {
        BackHandler {
            coroutineScope.launch { pagerState.animateScrollToPage(1) }
        }
    }

    // pendingScanResult 在顶层处理，避免 FingerprintManagerScreen 内部状态与顶层不同步
    LaunchedEffect(sharedViewModel.pendingScanResult) {
        val result = sharedViewModel.pendingScanResult ?: return@LaunchedEffect
        sharedViewModel.rawPolygonToEdit = result
        sharedViewModel.pendingScanResult = null
        sharedViewModel.isArScanning = false
    }

    // 导航栏显隐由 isArScanning / rawPolygonToEdit 同步驱动
    sharedViewModel.isBottomBarVisible = !(sharedViewModel.isArScanning || sharedViewModel.rawPolygonToEdit != null)

    // 导航栏高度固定 72dp，不用 Scaffold bottomBar，避免 innerPadding 动态变化引起内容区抖动
    val navBarHeight = 72.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // 内容区：底部固定预留 navBarHeight 的 padding，不随导航栏动画变化
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(bottom = navBarHeight),
            userScrollEnabled = false,
            beyondViewportPageCount = 3
        ) { page ->
            when (page) {
                0 -> BaseStationManagerScreen(sharedViewModel, 0.dp)
                1 -> FingerprintManagerScreen(sharedViewModel, 0.dp)
                2 -> PositioningTestScreen(sharedViewModel, 0.dp)
                3 -> SettingsScreen(sharedViewModel, 0.dp)
            }
        }

        // AR 扫描页：全屏悬浮在顶层，完全脱离 Pager 的 padding 约束
        AnimatedVisibility(
            visible = sharedViewModel.isArScanning,
            enter = slideInVertically(animationSpec = tween(350)) { it / 6 } + fadeIn(tween(350)),
            exit  = slideOutVertically(animationSpec = tween(250)) { it / 6 } + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            BackHandler(enabled = sharedViewModel.isArScanning) { sharedViewModel.isArScanning = false }
            ArScannerScreen(
                sharedViewModel = sharedViewModel,
                onComplete = { scanResult -> sharedViewModel.pendingScanResult = scanResult },
                onCancel   = { sharedViewModel.isArScanning = false }
            )
        }

        // 精修页：同样全屏悬浮，脱离 Pager padding
        if (!sharedViewModel.isArScanning && sharedViewModel.rawPolygonToEdit != null) {
            val clearEdit = {
                sharedViewModel.rawPolygonToEdit = null
                sharedViewModel.pendingBgPath = null
            }
            BackHandler { clearEdit() }
            MapVerificationScreen(
                scanResult      = sharedViewModel.rawPolygonToEdit!!,
                sharedViewModel = sharedViewModel,
                existingBgPath  = sharedViewModel.pendingBgPath,
                onSaveSuccess   = { clearEdit() },
                onDiscard       = { clearEdit() },
                onRescan        = { clearEdit(); sharedViewModel.isArScanning = true }
            )
        }

        // 导航栏悬浮在内容上方，动画只影响自身位置，不影响内容区 padding
        AnimatedVisibility(
            visible = sharedViewModel.isBottomBarVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val isDark = isSystemInDarkTheme()
            val overlayColor = if (isDark) Color(0xFF1A1A1A) else Color.White
            val navOverlayAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (sharedViewModel.isFabExpanded) 0.6f else 0f,
                animationSpec = tween(200),
                label = "navOverlay"
            )
            Box {
                NavigationBar(
                    modifier = Modifier.height(navBarHeight),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp
                ) {
                    val navIconsFilled   = listOf(Icons.Filled.CellTower,   Icons.Filled.Map,   Icons.Filled.Explore,   Icons.Filled.Settings)
                    val navIconsOutlined = listOf(Icons.Outlined.CellTower, Icons.Outlined.Map, Icons.Outlined.Explore, Icons.Outlined.Settings)
                    val navLabels = listOf("基站", "地图", "定位", "设置")
                    val selectedTabIndex = pagerState.targetPage

                    navLabels.forEachIndexed { index, label ->
                        val isSelected = selectedTabIndex == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            icon = { Icon(imageVector = if (isSelected) navIconsFilled[index] else navIconsOutlined[index], contentDescription = label) },
                            label = { Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
                // 导航栏遮罩叠层
                if (navOverlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(overlayColor.copy(alpha = navOverlayAlpha))
                            .clickable(enabled = false) {}
                    )
                }
            }
        }
    }
}