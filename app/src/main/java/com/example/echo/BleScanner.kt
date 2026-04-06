package com.example.echo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class BeaconDevice(
    val macAddress: String,
    var name: String?, // 🌟 修复：改为 var，允许迟到的设备名称动态解析并随时插队
    var rawRssi: Int,
    var smoothedRssi: Int,
    var lastSeen: Long,
    val firstSeen: Long = System.currentTimeMillis(),
    val filter: MovingAverageFilter = MovingAverageFilter(windowSize = 5)
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private val scannedDevicesMap = ConcurrentHashMap<String, BeaconDevice>()

    private val _scannedDevicesFlow = MutableStateFlow<List<BeaconDevice>>(emptyList())
    val scannedDevicesFlow: StateFlow<List<BeaconDevice>> = _scannedDevicesFlow.asStateFlow()

    private var lastUiUpdateTime = 0L // 🌟 UI 节流防抖时间戳

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val device = result.device
            val rssi = result.rssi
            val mac = device.address
            val deviceName = device.name

            val existingDevice = scannedDevicesMap[mac]
            var forceUpdate = false // 是否持有免死金牌，要求无视节流阀立刻刷新UI

            if (existingDevice != null) {
                synchronized(existingDevice) {
                    existingDevice.rawRssi = rssi
                    // 即使不立刻刷 UI，后台依旧在精准计算滑动均值
                    existingDevice.smoothedRssi = existingDevice.filter.addAndGetAverage(rssi)
                    existingDevice.lastSeen = System.currentTimeMillis()

                    // 🌟 如果设备原本无名，突然在后续广播里解析出了名字，立刻升级它的待遇
                    if (existingDevice.name.isNullOrBlank() && !deviceName.isNullOrBlank()) {
                        existingDevice.name = deviceName
                        forceUpdate = true
                    }
                }
            } else {
                val newFilter = MovingAverageFilter()
                val initialSmoothedRssi = newFilter.addAndGetAverage(rssi)
                val beacon = BeaconDevice(mac, deviceName, rssi, initialSmoothedRssi, System.currentTimeMillis(), System.currentTimeMillis(), newFilter)
                scannedDevicesMap[mac] = beacon
                forceUpdate = true // 🌟 发现新设备，拿到免死金牌，立刻强制刷新 UI！
            }

            val now = android.os.SystemClock.elapsedRealtime()

            // 🌟 引擎重构：节流阀机制 (Throttling)
            // 如果是新设备，立刻放行上屏；如果是老设备更新信号，限制最高 2Hz (500ms) 的刷新率
            // 这将彻底释放主线程 CPU 压力，让 LazyColumn 的排版过渡动画得以完美呈现！
            if (forceUpdate || now - lastUiUpdateTime > 500) {
                lastUiUpdateTime = now

                _scannedDevicesFlow.value = scannedDevicesMap.values
                    // 🌟 修复 1：移除 isReady() 的严苛限制，新扫到的设备 0 延迟上屏，解决“一时半会刷不出来”
                    .sortedWith(
                        // 🌟 修复 2：绝对的铁血多级排序法则
                        // 优先级 1: 有名字的设备永远高高在上，无名设备在下
                        compareByDescending<BeaconDevice> { !it.name.isNullOrBlank() }
                            // 优先级 2: 同级别下，信号强的在上面（把弱的顶下去）
                            .thenByDescending { it.smoothedRssi }
                            // 优先级 3: 信号相同时，论资排辈，防止老设备在列表中疯狂乱跳
                            .thenBy { it.firstSeen }
                    )
                    .toList()
            }
        }
    }

    fun startScan() {
        scannedDevicesMap.clear()
        _scannedDevicesFlow.value = emptyList() // 🌟 启动时先清空残留 UI
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        scanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    fun clearDevices() {
        scannedDevicesMap.clear()
        _scannedDevicesFlow.value = emptyList()
    }
}