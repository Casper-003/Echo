package com.example.echo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseStationManagerScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val displayedDevices by scanner.scannedDevicesFlow.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val selectedDevices = sharedViewModel.selectedDevices
    val onSelectionChange: (Map<String, BeaconDevice>) -> Unit = { sharedViewModel.selectedDevices = it }
    val unselectedDevices = remember(displayedDevices, selectedDevices, sharedViewModel.isIgnoreUnnamedEnabled) {
        displayedDevices.filter { it.macAddress !in selectedDevices.keys && (!sharedViewModel.isIgnoreUnnamedEnabled || !it.name.isNullOrBlank()) }
    }

    DisposableEffect(Unit) { if (isScanning) scanner.startScan(); onDispose { scanner.stopScan() } }
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 颜色平滑过渡
    val buttonColor by animateColorAsState(
        targetValue = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 400),
        label = "buttonColor"
    )
    val onScanClick = {
        if (isScanning) scanner.stopScan() else scanner.startScan()
        isScanning = !isScanning
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text("基站管理", fontWeight = FontWeight.Bold) }, scrollBehavior = scrollBehavior, windowInsets = WindowInsets.statusBars, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface), titleContentColor = MaterialTheme.colorScheme.onSurface)) }
    ) { innerPadding ->
        val cartUI = @Composable { modifier: Modifier, isLandscape: Boolean ->
            Column(modifier = modifier.padding(16.dp)) {
                Text("已锁定基站 (${selectedDevices.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                if (isLandscape) { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) { items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device -> SelectedDeviceChip(device = device, modifier = Modifier.animateItem().fillMaxWidth()) { onSelectionChange(selectedDevices - device.macAddress) } } } }
                else { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device -> SelectedDeviceChip(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices - device.macAddress) } } } }
            }
        }
        val pullRefreshState = rememberPullToRefreshState()
        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1.5f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = onScanClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            shape = RoundedCornerShape(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                        ) {
                            AnimatedContent(
                                targetState = isScanning,
                                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                                label = "buttonContent"
                            ) { scanning ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (scanning) Icons.Rounded.Stop else Icons.Rounded.Bluetooth,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (scanning) "停止扫描" else "开始扫描",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    val wideListState = rememberLazyListState()
                    val showWideBackToTop by remember {
                        derivedStateOf { wideListState.firstVisibleItemIndex >= 10 }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } }, modifier = Modifier.fillMaxSize()) {
                            LazyColumn(state = wideListState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = bottomPadding)) { items(unselectedDevices, key = { it.macAddress }) { device -> DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) } } }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showWideBackToTop,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 16.dp)
                        ) {
                            FloatingActionButton(
                                onClick = { coroutineScope.launch { wideListState.animateScrollToItem(0) } },
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "回到顶部", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                // 🌟 平板模式动画滑入购物车
                AnimatedVisibility(
                    visible = selectedDevices.isNotEmpty(),
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)).padding(top = innerPadding.calculateTopPadding()))
                        cartUI(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)).padding(top = innerPadding.calculateTopPadding()), true)
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val showBackToTop by remember {
                derivedStateOf { listState.firstVisibleItemIndex >= 10 }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = onScanClick,
                        modifier = Modifier
                            .height(80.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                    ) {
                        AnimatedContent(
                            targetState = isScanning,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                            label = "buttonContent"
                        ) { scanning ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (scanning) Icons.Rounded.Stop else Icons.Rounded.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (scanning) "停止扫描" else "开始扫描",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))) {
                    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } }, state = pullRefreshState, indicator = { Indicator(modifier = Modifier.align(Alignment.TopCenter), isRefreshing = isRefreshing, state = pullRefreshState) }, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 0.dp), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(unselectedDevices, key = { it.macAddress }) { device -> DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) } } }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showBackToTop,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "回到顶部", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                AnimatedVisibility(visible = selectedDevices.isNotEmpty()) { Column(modifier = Modifier.fillMaxWidth()) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)); cartUI(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), false) } }
                Spacer(modifier = Modifier.height(bottomPadding))
            }
        }
    }
}

@Composable
fun DeviceItemCard(device: BeaconDevice, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hasName = !device.name.isNullOrEmpty(); val displayName = if (hasName) device.name!! else "未知设备"; val nameColor = if (hasName) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (hasName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(text = displayName, color = nameColor, fontWeight = FontWeight.Bold); Text(text = device.macAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Text(text = "${device.smoothedRssi} dBm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (device.smoothedRssi > -60) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun SelectedDeviceChip(device: BeaconDevice, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    Card(onClick = onRemove, modifier = modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Text(text = device.name ?: "未知设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(text = device.macAddress.takeLast(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)) }; Spacer(modifier = Modifier.width(8.dp)); Icon(Icons.Default.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
        }
    }
}
