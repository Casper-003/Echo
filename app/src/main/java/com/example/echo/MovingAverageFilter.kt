package com.example.echo

/**
 * 移动平均滤波 (Moving Average Filtering)
 * 对应论文 2.2.2 节算法：平滑 RSSI 信号毛刺
 */
class MovingAverageFilter(private val windowSize: Int = 5) {

    // 滑动窗口队列
    private val window = ArrayDeque<Int>(windowSize)
    private var lastInput: Int? = null // 上一次入窗口的值，用于去重

    fun addAndGetAverage(newRssi: Int): Int {
        // 重复值不入窗口，直接返回当前均值（消除阶梯采样的假斜坡）
        if (newRssi == lastInput && window.isNotEmpty()) {
            return window.sum() / window.size
        }
        lastInput = newRssi
        // 如果窗口满了，踢出最老的值
        if (window.size >= windowSize) {
            window.removeFirst()
        }
        // 加入最新值
        window.addLast(newRssi)
        // 计算平均值
        return window.sum() / window.size
    }

    fun clear() {
        window.clear()
        lastInput = null
    }

    // 🌟 新增：冷启动防漂移机制
    // 只有当滑动窗口被完全填满时，才认为当前滤波数据具备统计学意义
    fun isReady(): Boolean {
        return window.size == windowSize
    }
}