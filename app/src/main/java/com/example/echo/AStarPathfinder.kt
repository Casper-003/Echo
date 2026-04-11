package com.example.echo

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

// ================= A* 离散空间网格寻路引擎 (完美适配 AR 异形多边形版) =================
class AStarPathfinder {

    data class Node(
        val col: Int, val row: Int,
        var g: Double = Double.MAX_VALUE, var h: Double = 0.0,
        var parent: Node? = null
    ) : Comparable<Node> {
        val f: Double get() = g + h
        override fun compareTo(other: Node): Int = this.f.compareTo(other.f)
    }

    // 🌟 核心升级：Ray-Casting 射线法，判断点是否在任意不规则多边形内部
    private fun isPointInPolygon(point: Point, polygon: List<Point>): Boolean {
        if (polygon.size < 3) return true // 降级兼容：如果没有合法多边形，默认全图可通行
        var isInside = false
        var i = 0
        var j = polygon.size - 1
        while (i < polygon.size) {
            val pi = polygon[i]
            val pj = polygon[j]
            // 射线与多边形边相交的奇偶性测试
            val intersect = ((pi.y > point.y) != (pj.y > point.y)) &&
                    (point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x)
            if (intersect) isInside = !isInside
            j = i++
        }
        return isInside
    }

    private fun findNearestWalkable(startC: Int, startR: Int, cols: Int, rows: Int, isObstacle: Array<BooleanArray>): Pair<Int, Int>? {
        if (startC in 0 until cols && startR in 0 until rows && !isObstacle[startC][startR]) return Pair(startC, startR)
        val queue = ArrayDeque<Pair<Int, Int>>()
        val visited = mutableSetOf<String>()
        queue.add(Pair(startC, startR)); visited.add("${startC}_${startR}")

        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            val c = curr.first; val r = curr.second
            if (c in 0 until cols && r in 0 until rows && !isObstacle[c][r]) return curr

            val dirs = listOf(Pair(0,1), Pair(0,-1), Pair(1,0), Pair(-1,0), Pair(1,1), Pair(-1,-1), Pair(1,-1), Pair(-1,1))
            for (d in dirs) {
                val nc = c + d.first; val nr = r + d.second
                if (kotlin.math.abs(nc - startC) <= 3 && kotlin.math.abs(nr - startR) <= 3) {
                    val key = "${nc}_${nr}"
                    if (visited.add(key)) queue.add(Pair(nc, nr))
                }
            }
        }
        return null
    }

    // 🌟 核心升级：将原先的 w, l 替换为 mapPolygon
    fun findPath(
        start: Point,
        target: Point,
        mapPolygon: List<Point>,
        gridResolution: Double,
        obstacles: List<Point>
    ): List<Point> {
        // 1. 根据多边形的包围盒 (Bounding Box) 确定最大网格范围
        val maxX = mapPolygon.maxOfOrNull { it.x } ?: 10.0
        val maxY = mapPolygon.maxOfOrNull { it.y } ?: 10.0
        val cols = ceil(maxX / gridResolution).toInt() + 1
        val rows = ceil(maxY / gridResolution).toInt() + 1

        if (cols <= 0 || rows <= 0) return emptyList()

        val isObstacle = Array(cols) { BooleanArray(rows) }

        // 2. 天然空气墙生成：遍历所有网格，如果对应指纹点不在 AR 多边形内则标记为障碍
        // 对边界点用微小 epsilon 扰动，避免射线法在多边形边上的数值不稳定问题
        if (mapPolygon.size >= 3) {
            val eps = gridResolution * 1e-4
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    val px = c * gridResolution
                    val py = r * gridResolution
                    // 原点 + 微扰点，任一在多边形内则视为可通行
                    val inPoly = isPointInPolygon(Point(px, py), mapPolygon) ||
                                 isPointInPolygon(Point(px + eps, py + eps), mapPolygon)
                    if (!inPoly) {
                        isObstacle[c][r] = true
                    }
                }
            }
        }

        // 3. 附加用户手动绘制的物理墙体
        for (obs in obstacles) {
            val c = floor(obs.x / gridResolution).toInt()
            val r = floor(obs.y / gridResolution).toInt()
            if (c in 0 until cols && r in 0 until rows) isObstacle[c][r] = true
        }

        val rawStartC = floor(start.x / gridResolution).toInt(); val rawStartR = floor(start.y / gridResolution).toInt()
        val rawTargetC = floor(target.x / gridResolution).toInt(); val rawTargetR = floor(target.y / gridResolution).toInt()

        val safeStart = findNearestWalkable(rawStartC, rawStartR, cols, rows, isObstacle) ?: return emptyList()
        val safeTarget = findNearestWalkable(rawTargetC, rawTargetR, cols, rows, isObstacle) ?: return emptyList()

        val startCol = safeStart.first; val startRow = safeStart.second
        val targetCol = safeTarget.first; val targetRow = safeTarget.second

        val openList = PriorityQueue<Node>()
        val closedSet = mutableSetOf<String>()
        val allNodes = mutableMapOf<String, Node>()

        fun getNodeId(c: Int, r: Int) = "${c}_${r}"

        val startNode = Node(startCol, startRow, g = 0.0, h = calculateHeuristic(startCol, startRow, targetCol, targetRow))
        openList.add(startNode); allNodes[getNodeId(startCol, startRow)] = startNode

        val directions = listOf(
            Pair(0, -1) to 1.0, Pair(0, 1) to 1.0, Pair(-1, 0) to 1.0, Pair(1, 0) to 1.0,
            Pair(-1, -1) to 1.414, Pair(1, -1) to 1.414, Pair(-1, 1) to 1.414, Pair(1, 1) to 1.414
        )

        var targetNode: Node? = null

        while (openList.isNotEmpty()) {
            val current = openList.poll() ?: break
            if (current.col == targetCol && current.row == targetRow) { targetNode = current; break }
            closedSet.add(getNodeId(current.col, current.row))

            for ((dir, cost) in directions) {
                val neighborCol = current.col + dir.first; val neighborRow = current.row + dir.second
                if (neighborCol !in 0 until cols || neighborRow !in 0 until rows) continue
                if (isObstacle[neighborCol][neighborRow]) continue
                if (cost > 1.0 && (isObstacle[current.col][neighborRow] || isObstacle[neighborCol][current.row])) continue

                val neighborId = getNodeId(neighborCol, neighborRow)
                if (closedSet.contains(neighborId)) continue

                val tentativeG = current.g + cost
                var neighborNode = allNodes[neighborId]
                if (neighborNode == null) {
                    neighborNode = Node(neighborCol, neighborRow, tentativeG, calculateHeuristic(neighborCol, neighborRow, targetCol, targetRow), current)
                    allNodes[neighborId] = neighborNode; openList.add(neighborNode)
                } else if (tentativeG < neighborNode.g) {
                    neighborNode.g = tentativeG; neighborNode.parent = current
                    openList.remove(neighborNode); openList.add(neighborNode)
                }
            }
        }

        if (targetNode == null) return emptyList()

        val pathNodes = mutableListOf<Node>()
        var curr: Node? = targetNode
        while (curr != null) { pathNodes.add(curr); curr = curr.parent }
        pathNodes.reverse()

        val physicalPath = pathNodes.map { node ->
            Point((node.col * gridResolution) + (gridResolution / 2.0), (node.row * gridResolution) + (gridResolution / 2.0))
        }.toMutableList()

        if (physicalPath.isNotEmpty()) {
            physicalPath[0] = start; physicalPath[physicalPath.size - 1] = target
        }
        return smoothPath(physicalPath, gridResolution, isObstacle, cols, rows)
    }

    private fun calculateHeuristic(c1: Int, r1: Int, c2: Int, r2: Int): Double {
        val dx = kotlin.math.abs(c1 - c2).toDouble(); val dy = kotlin.math.abs(r1 - r2).toDouble()
        return ((dx + dy) + (1.414 - 2.0) * minOf(dx, dy)) * 1.001
    }

    private fun smoothPath(path: List<Point>, s: Double, isObstacle: Array<BooleanArray>, cols: Int, rows: Int): List<Point> {
        if (path.size <= 2) return path
        val smoothed = mutableListOf<Point>(); smoothed.add(path.first())
        var currentIndex = 0
        while (currentIndex < path.size - 1) {
            var furthest = currentIndex + 1
            for (i in currentIndex + 2 until path.size) {
                if (hasLineOfSight(path[currentIndex], path[i], s, isObstacle, cols, rows)) { furthest = i }
            }
            smoothed.add(path[furthest]); currentIndex = furthest
        }
        return smoothed
    }

    private fun hasLineOfSight(p1: Point, p2: Point, s: Double, isObstacle: Array<BooleanArray>, cols: Int, rows: Int): Boolean {
        val dist = hypot(p2.x - p1.x, p2.y - p1.y)
        if (dist == 0.0) return true
        val steps = ceil(dist / (s / 10.0)).toInt(); val margin = s * 0.4
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val cx = p1.x + t * (p2.x - p1.x); val cy = p1.y + t * (p2.y - p1.y)
            val checkPoints = listOf(Point(cx, cy), Point(cx - margin, cy), Point(cx + margin, cy), Point(cx, cy - margin), Point(cx, cy + margin), Point(cx - margin, cy - margin), Point(cx + margin, cy - margin), Point(cx - margin, cy + margin), Point(cx + margin, cy + margin))
            for (pt in checkPoints) {
                val c = floor(pt.x / s).toInt(); val r = floor(pt.y / s).toInt()
                if (c in 0 until cols && r in 0 until rows && isObstacle[c][r]) return false
            }
        }
        return true
    }
}