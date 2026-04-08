package com.example.echo

import kotlin.math.sqrt

// 物理坐标点
data class Point(val x: Double, val y: Double)

// 对应论文中的“参考点 (Reference Point, RP)”
data class ReferencePoint(
    val id: String,
    val coordinate: Point,
    val fingerprint: Map<String, Int>
)

// 🌟 新增：包含算法分析指标的详细返回体
data class LocateResult(
    val coordinate: Point,
    val d1: Double,       // 最小匹配特征距离 (用于评估信号环境异构度)
    val validN: Int,      // 参与本次解算的有效特征维度数
    val dynamicK: Int     // 🌟 新增：记录 AWKNN 实际使用的动态截断 K 值
)

/**
 * 核心定位解算器 (WKNN Algorithm Engine)
 */
class WknnLocator {

    private fun calculateEuclideanDistance(liveRssi: Map<String, Int>, rpFingerprint: Map<String, Int>): Double {
        val commonMacs = liveRssi.keys.intersect(rpFingerprint.keys)
        if (commonMacs.size < 2) return Double.MAX_VALUE

        var sumSq = 0.0
        for (mac in commonMacs) {
            val s_j = liveRssi[mac]!!
            val f_ij = rpFingerprint[mac]!!
            val diff = (s_j - f_ij).toDouble()
            sumSq += diff * diff
        }

        return sqrt(sumSq / commonMacs.size)
    }

    // 🌟 核心：为 Benchmark 准备了完整的多算法支持
    fun locateDetailed(
        liveRssi: Map<String, Int>,
        database: List<ReferencePoint>,
        k: Int,
        useWknn: Boolean = true,
        useAwknn: Boolean = false, // AWKNN 模式开关
        rho: Double = 1.5,         // 扩展惩罚因子，经验范围 1.2~2.0
        tMin: Double = 1.5         // 阈值底噪，防止强 LOS 下邻域退化为单点，经验范围 1.0~3.0
    ): LocateResult? {
        if (liveRssi.isEmpty() || database.isEmpty()) return null

        val distances = database.map { rp ->
            Triple(rp, calculateEuclideanDistance(liveRssi, rp.fingerprint), liveRssi.keys.intersect(rp.fingerprint.keys).size)
        }.filter { it.second != Double.MAX_VALUE }

        if (distances.isEmpty()) return null

        val sortedCandidates = distances.sortedBy { it.second }

        // 🌟 AWKNN 核心：动态计算截断 K 值
        // threshold = max(rho * d1, tMin)：tMin 防止强 LOS 下 d1→0 导致邻域退化为单点
        val finalK = if (useAwknn && sortedCandidates.isNotEmpty()) {
            val d1 = sortedCandidates.first().second
            val threshold = maxOf(d1 * rho, tMin)
            val dynamicK = sortedCandidates.count { it.second <= threshold }
            dynamicK.coerceIn(1, minOf(5, sortedCandidates.size))
        } else {
            k.coerceAtMost(sortedCandidates.size).coerceAtLeast(1)
        }

        val nearestNeighbors = sortedCandidates.take(finalK)
        if (nearestNeighbors.isEmpty()) return null

        // 提取学术指标
        val d1 = sortedCandidates.first().second
        val validN = sortedCandidates.first().third

        val finalPoint = if (useWknn) {
            var weightSum = 0.0
            var xSum = 0.0
            var ySum = 0.0
            for (neighbor in nearestNeighbors) {
                val distance = neighbor.second
                // 修复：引入极小常量 epsilon (1e-6)，绝对杜绝物理重合导致的除零崩溃
                val safeDistance = distance + 1e-6
                val weight = 1.0 / safeDistance
                weightSum += weight
                xSum += neighbor.first.coordinate.x * weight
                ySum += neighbor.first.coordinate.y * weight
            }
            if (weightSum == 0.0) return null
            Point(xSum / weightSum, ySum / weightSum)
        } else {
            val avgX = nearestNeighbors.map { it.first.coordinate.x }.average()
            val avgY = nearestNeighbors.map { it.first.coordinate.y }.average()
            Point(avgX, avgY)
        }

        // 🌟 将 finalK (实际计算采用的 K 值) 一并返回
        return LocateResult(finalPoint, d1, validN, finalK)
    }

    // 兼容原有的 UI 渲染调用
    fun locate(liveRssi: Map<String, Int>, database: List<ReferencePoint>, k: Int, useWknn: Boolean = true): Point? {
        return locateDetailed(liveRssi, database, k, useWknn, false, 1.5)?.coordinate
    }
}