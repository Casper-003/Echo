package com.example.echo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

// ================= 基于特征距离的自适应动态互补融合定位引擎 =================
class LocationFusionEngine(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 全局最优估计坐标 (lastFused)
    private val _fusedPosition = MutableStateFlow<Point?>(null)
    val fusedPosition: StateFlow<Point?> = _fusedPosition.asStateFlow()

    private val _currentHeadingFlow = MutableStateFlow(0f)
    val currentHeadingFlow: StateFlow<Float> = _currentHeadingFlow.asStateFlow()

    // 纯推算对比轨迹 (永远不受蓝牙纠偏，为实验三对照组保留)
    private val _purePdrPosition = MutableStateFlow<Point?>(null)
    val purePdrPosition: StateFlow<Point?> = _purePdrPosition.asStateFlow()

    // 🌟 新增：暴露动态权重 W_ble 供日志记录
    private val _currentBleWeight = MutableStateFlow(0.0)
    val currentBleWeight: StateFlow<Double> = _currentBleWeight.asStateFlow()

    private var currentHeading: Double = 0.0
    private var headingOffset: Double = 0.0

    private val stepLength = 0.6

    // 🌟 动态权重经验阈值
    private val GOOD_SIGNAL_D = 2.0
    private val BAD_SIGNAL_D = 8.0
    // BLE 权重上限：保留 PDR 最低 15% 平滑贡献，抑制强 LOS 下的微观抖动暴露，经验范围 0.80~0.90
    private val MAX_BLE_WEIGHT = 0.85

    fun start() {
        stepDetector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // 🌟 融合阶段 (Update - 由 AWKNN 蓝牙扫描触发)
    fun updateWknnPosition(blePoint: Point, minD1: Double) = synchronized(this) {
        val lastFused = _fusedPosition.value
        if (lastFused == null) {
            _fusedPosition.value = blePoint
            _purePdrPosition.value = blePoint
            _currentBleWeight.value = 1.0
        } else {
            // 1. 计算动态信任权重 (Linear Mapping，分段线性近似指数衰减)
            val wBle = when {
                minD1 <= GOOD_SIGNAL_D -> MAX_BLE_WEIGHT
                minD1 >= BAD_SIGNAL_D -> 0.1
                else -> MAX_BLE_WEIGHT - ((minD1 - GOOD_SIGNAL_D) / (BAD_SIGNAL_D - GOOD_SIGNAL_D)) * (MAX_BLE_WEIGHT - 0.1)
            }
            _currentBleWeight.value = wBle

            // 2. 极简互补融合公式 (Complementary Fusion)
            // 此时的 lastFused 已经包含了 PDR 累加的位移
            val fusedX = wBle * blePoint.x + (1.0 - wBle) * lastFused.x
            val fusedY = wBle * blePoint.y + (1.0 - wBle) * lastFused.y

            // 3. 状态更新
            _fusedPosition.value = Point(fusedX, fusedY)
        }
    }

    fun calibrateHeading() {
        headingOffset = -currentHeading
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                currentHeading = orientationAngles[0].toDouble()
                val mapHeading = currentHeading + headingOffset
                _currentHeadingFlow.value = Math.toDegrees(mapHeading).toFloat()
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                synchronized(this) {
                    val lastFused = _fusedPosition.value
                    val currentPurePdr = _purePdrPosition.value

                    if (lastFused != null && currentPurePdr != null) {
                        val mapHeading = currentHeading + headingOffset

                        // 计算物理位移 deltaX 和 deltaY
                        val dx = stepLength * sin(mapHeading)
                        val dy = -stepLength * cos(mapHeading)

                        // 🌟 预测阶段 (Prediction)
                        // 将推算坐标作为临时最优解直接覆写回全局状态
                        _fusedPosition.value = Point(lastFused.x + dx, lastFused.y + dy)

                        // 纯 PDR 对照组同步发散
                        _purePdrPosition.value = Point(currentPurePdr.x + dx, currentPurePdr.y + dy)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}