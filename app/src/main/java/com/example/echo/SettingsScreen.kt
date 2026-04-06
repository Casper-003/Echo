package com.example.echo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 分区枚举（不含 ABOUT，关于组直接平铺在主列表）
enum class SettingsSection(
    val icon: ImageVector,
    val title: String,
    val subtitle: String
) {
    THEME(Icons.Default.Palette, "主题与外观", "主题色、深色模式"),
    RADAR(Icons.Default.Sensors, "雷达引擎配置", "导航、采集、算法开关"),
    MANUAL(Icons.Default.MenuBook, "系统操作手册", "部署、建库、避障与导航说明"),
    DATA(Icons.Default.Delete, "清空本地缓存", "清除已锁定的基站与所有未导出的指纹快照")
}

// 分组标题
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

// 主列表入口行（扁平，无卡片）
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FlatNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    titleColor: Color,
    sharedKey: String,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onClick: () -> Unit
) {
    with(sharedTransitionScope) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(
                    rememberSharedContentState(key = sharedKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    var currentSection by remember { mutableStateOf<SettingsSection?>(null) }
    LaunchedEffect(currentSection) {
        sharedViewModel.isSettingsDetailOpen = currentSection != null
    }
    DisposableEffect(Unit) {
        onDispose { sharedViewModel.isSettingsDetailOpen = false }
    }
    BackHandler(enabled = currentSection != null) { currentSection = null }

    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    SharedTransitionLayout {
        AnimatedContent(
            targetState = currentSection,
            transitionSpec = {
                fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith
                        fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
            label = "settingsTransition"
        ) { section ->
            if (section == null) {
                // ── 主列表 ──
                val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = Color.Transparent,
                    topBar = {
                        TopAppBar(
                            title = { Text("系统设置", fontWeight = FontWeight.Bold) },
                            scrollBehavior = scrollBehavior,
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    .compositeOver(MaterialTheme.colorScheme.surface),
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = bottomPadding + 32.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // ── 外观 ──
                        item {
                            SectionHeader("外观")
                            FlatNavItem(
                                icon = SettingsSection.THEME.icon,
                                title = SettingsSection.THEME.title,
                                subtitle = SettingsSection.THEME.subtitle,
                                iconTint = MaterialTheme.colorScheme.primary,
                                titleColor = MaterialTheme.colorScheme.onSurface,
                                sharedKey = "card_${SettingsSection.THEME.name}",
                                animatedVisibilityScope = this@AnimatedContent,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                onClick = { currentSection = SettingsSection.THEME }
                            )
                        }

                        // ── 引擎配置 ──
                        item {
                            HorizontalDivider(color = dividerColor)
                            SectionHeader("引擎配置")
                            FlatNavItem(
                                icon = SettingsSection.RADAR.icon,
                                title = SettingsSection.RADAR.title,
                                subtitle = SettingsSection.RADAR.subtitle,
                                iconTint = MaterialTheme.colorScheme.primary,
                                titleColor = MaterialTheme.colorScheme.onSurface,
                                sharedKey = "card_${SettingsSection.RADAR.name}",
                                animatedVisibilityScope = this@AnimatedContent,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                onClick = { currentSection = SettingsSection.RADAR }
                            )
                        }

                        // ── 使用指南 ──
                        item {
                            HorizontalDivider(color = dividerColor)
                            SectionHeader("使用指南")
                            FlatNavItem(
                                icon = SettingsSection.MANUAL.icon,
                                title = SettingsSection.MANUAL.title,
                                subtitle = SettingsSection.MANUAL.subtitle,
                                iconTint = MaterialTheme.colorScheme.primary,
                                titleColor = MaterialTheme.colorScheme.onSurface,
                                sharedKey = "card_${SettingsSection.MANUAL.name}",
                                animatedVisibilityScope = this@AnimatedContent,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                onClick = { currentSection = SettingsSection.MANUAL }
                            )
                        }

                        // ── 关于（直接平铺，无子页）──
                        item {
                            HorizontalDivider(color = dividerColor)
                            SectionHeader("关于")
                        }
                        item {
                            SettingClickableItem(
                                icon = Icons.Default.Info,
                                title = "开源声明与技术栈",
                                subtitle = "Jetpack Compose & PDR Fusion Engine",
                                onClick = { showLicenseDialog = true }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = dividerColor
                            )
                            SettingClickableItem(
                                icon = Icons.Default.Person,
                                title = "开发者",
                                subtitle = "Casper-003",
                                onClick = { Toast.makeText(context, "感谢使用 Echo，给个Star谢谢喵！", Toast.LENGTH_SHORT).show() }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = dividerColor
                            )
                            EmailSettingItem(context = context)
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = dividerColor
                            )
                            SettingClickableItem(
                                icon = Icons.Default.Share,
                                title = "开源仓库 (GitHub)",
                                subtitle = "Echo",
                                onClick = {
                                    try { uriHandler.openUri("https://github.com/Casper-003/Echo") }
                                    catch (e: Exception) { Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show() }
                                }
                            )
                        }

                        // ── 危险操作 ──
                        item {
                            HorizontalDivider(color = dividerColor)
                            SectionHeader("危险操作")
                            SettingClickableItem(
                                icon = SettingsSection.DATA.icon,
                                title = SettingsSection.DATA.title,
                                subtitle = SettingsSection.DATA.subtitle,
                                isDestructive = true,
                                onClick = { showClearConfirm = true }
                            )
                        }

                        // ── 版本信息（底部）──
                        item {
                            HorizontalDivider(color = dividerColor)
                            Spacer(Modifier.height(32.dp))
                            Text(
                                "Echo v4.0.1\nPowered by Kotlin & Jetpack Compose",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }

            } else {
                // ── 子页面 ──
                val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedBounds(
                            rememberSharedContentState(key = "card_${section.name}"),
                            animatedVisibilityScope = this@AnimatedContent,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()
                        )
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = Color.Transparent,
                    topBar = {
                        TopAppBar(
                            title = { Text(section.title, fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                IconButton(onClick = { currentSection = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            scrollBehavior = scrollBehavior,
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    .compositeOver(MaterialTheme.colorScheme.surface),
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    when (section) {
                        SettingsSection.THEME -> ThemeSectionContent(sharedViewModel, innerPadding, bottomPadding)
                        SettingsSection.RADAR -> RadarSectionContent(sharedViewModel, innerPadding, bottomPadding)
                        SettingsSection.MANUAL -> ManualSectionContent(innerPadding, bottomPadding)
                        SettingsSection.DATA -> {} // 不走子页，直接弹 Dialog
                    }
                }
            }
        }
    }

    // ── Dialogs ──
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("警告", fontWeight = FontWeight.Bold) },
            text = { Text("您确定要清空所有数据吗？此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        sharedViewModel.updateRecordedPoints(emptyList())
                        showClearConfirm = false
                        Toast.makeText(context, "缓存已清空", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }
    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("⚖️ 开源技术声明", fontWeight = FontWeight.Bold) },
            text = { Text("本系统构建于以下现代移动开发技术栈：\n\n• Kotlin Coroutines & Flow\n• Jetpack Compose Material 3\n• Android BLE API & Sensors\n• Room Database\n\n核心定位引擎由开发者自主实现，采用了针对 RSSI 信号优化的 AWKNN 算法及 PDR (航位推算) 多传感器融合技术。") },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") } }
        )
    }
}

// ════════════════════════════════════════════════
// 子页内容
// ════════════════════════════════════════════════

@Composable
private fun ThemeSectionContent(
    sharedViewModel: SharedViewModel,
    innerPadding: PaddingValues,
    bottomPadding: Dp
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    LazyColumn(
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            // 主题色轮
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {
                items(ThemePreset.values()) { preset ->
                    val isSelected = sharedViewModel.currentThemePreset == preset
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (preset == ThemePreset.DYNAMIC) MaterialTheme.colorScheme.surfaceVariant else preset.color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { sharedViewModel.changeTheme(preset) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (preset == ThemePreset.DYNAMIC) Text("🌈", style = MaterialTheme.typography.titleMedium)
                            else if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = preset.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            lineHeight = 12.sp,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 76.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = dividerColor)
        }
        item {
            // 深色模式
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text("深色模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                SegmentedButton(
                    options = DarkModeConfig.values().map { it.title },
                    selectedIndex = DarkModeConfig.values().indexOf(sharedViewModel.darkModeConfig),
                    onOptionSelected = { sharedViewModel.updateDarkModeConfig(DarkModeConfig.values()[it]) }
                )
            }
            HorizontalDivider(color = dividerColor)
        }
    }
}

@Composable
private fun RadarSectionContent(
    sharedViewModel: SharedViewModel,
    innerPadding: PaddingValues,
    bottomPadding: Dp
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    LazyColumn(
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SettingSwitchItem(Icons.Default.LocationOn, "第一人称导航", "开启后地图将反向平移与旋转，当前定位坐标始终固定在屏幕中央", sharedViewModel.isMapFollowingModeEnabled) { sharedViewModel.setMapFollowingMode(it) }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = dividerColor)
            SettingSwitchItem(Icons.Default.Search, "启动时自动扫描", "进入应用后自动开启低功耗蓝牙 (BLE) 扫描", sharedViewModel.autoScan) { sharedViewModel.setAutoScanState(it) }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = dividerColor)
            SettingSwitchItem(Icons.Default.CheckCircle, "过滤无名设备", "在基站管理页屏蔽未广播名称的隐藏或乱码设备，保持列表整洁", sharedViewModel.isIgnoreUnnamedEnabled) { sharedViewModel.setIgnoreUnnamed(it) }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = dividerColor)
            SettingSwitchItem(Icons.Default.Refresh, "360° 全向高精度采集", "开启后，采集指纹时需原地旋转一圈以获取抗遮挡的平均信号", sharedViewModel.is360CollectionModeEnabled) { sharedViewModel.set360CollectionMode(it) }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = dividerColor)
            SettingSwitchItem(Icons.Default.Build, "性能评估模式", "开启后可在定位页手动调节 AWKNN 算法参数并测算误差", sharedViewModel.isAdvancedModeEnabled) { sharedViewModel.setAdvancedMode(it) }
            HorizontalDivider(color = dividerColor)
        }
    }
}

@Composable
private fun ManualSectionContent(innerPadding: PaddingValues, bottomPadding: Dp) {
    LazyColumn(
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { ManualSectionItem("一、 基站部署与锁定", "1. 硬件建议：推荐 ESP32 广播频率设为 20Hz (50ms)，发射功率降至 0dBm 或 -3dBm 以构建良好的室内空间信号衰减梯度。\n\n2. 基站锁定：在【基站】页面等待扫描出物理基站，务必手动勾选至少 3 个基站。锁定后，指纹采集与定位解算将严格基于这几个基站的信号源。") }
        item { ManualSectionItem("二、 指纹采集与建库", "1. 地图初始化：在【指纹】页面设定物理空间的宽、长和网格间距（建议 1~2 米）。\n\n2. 单点采集 (⚡)：点击地图选定格子，点击采集，系统瞬间记录当前 RSSI 均值入库。\n\n3. 360° 发条模式 (↻)：勾选后，选定点位点击开始，需手持设备朝着同一个方向缓慢旋转 360 度。系统会收集全方位极化信号，大幅提高精度。\n\n4. 数据备份：所有打点完成后，务必点击【导出备份】生成 CSV 存档，防止重装 App 导致数据丢失。") }
        item {
            ManualSectionItem("三、 避障绘图与导航", """1. 绘制墙体：进入【定位】页的"避障编辑"模式，通过手指划动可在地图上浇筑灰色的物理墙体。系统自带绝对防穿模的 0.4s 体积膨胀及空气墙边缘防护。

2. A* 平滑导航：在"定位导航"模式下选定紫色的目标点。系统会自动避开墙体、走在走廊正中间。右上角 HUD 将实时计算真实的"折线行走总路程"。""")
        }
        item {
            ManualSectionItem("四、 实验评估与导出", """请先开启【开发者性能评估模式】以解锁算法面板。

1. 误差测绘：对比生数据(RAW)、传统平滑(EMA)、多模态融合(PDR)对漂移的抑制效果。

2. 定点空间实验 (50次)：验证环境变化对定点精度的影响。自动高频采集 50 个样本。

3. 定点时间实验 (60秒)：手机静置 60 秒连续录制，用于绘制"时间-坐标(X/Y)"滤波折线图。

4. 自由轨迹录制 (2Hz)：无需点选地图，直接开启录制并在场地内走动，系统会以 2Hz 频率连续记录 X/Y 轨迹。

* 提示：测试产生的数据存放于 Android/data/.../files/ 目录下。""")
        }
    }
}

// ════════════════════════════════════════════════
// 复用组件
// ════════════════════════════════════════════════

@Composable
fun ManualSectionItem(title: String, content: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun SettingSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = contentColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (isDestructive) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        if (!isDestructive) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmailSettingItem(context: android.content.Context) {
    val email = "casper-003@outlook.com"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                    try { context.startActivity(intent) }
                    catch (e: Exception) { Toast.makeText(context, "未找到可用的邮件应用", Toast.LENGTH_SHORT).show() }
                },
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("email", email))
                    Toast.makeText(context, "邮件地址已复制", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("联系邮箱", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
    }
}
