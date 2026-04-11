#include <jni.h>
#include <cmath>
#include <cfloat>
#include <vector>
#include <algorithm>
#include <unordered_set>
#include <unordered_map>
#include <queue>

// =====================================================================================
// 内部数据结构
// =====================================================================================
struct Pt {
    float x, z;
};

// =====================================================================================
// 凸包：Andrew's Monotone Chain（逆时针）
// =====================================================================================
static float cross(const Pt& o, const Pt& a, const Pt& b) {
    return (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x);
}

static std::vector<Pt> convexHull(std::vector<Pt> pts) {
    int n = (int)pts.size();
    if (n <= 2) return pts;

    std::sort(pts.begin(), pts.end(), [](const Pt& a, const Pt& b) {
        return a.x < b.x || (a.x == b.x && a.z < b.z);
    });

    std::vector<Pt> h;
    // 下凸壳
    for (int i = 0; i < n; i++) {
        while (h.size() >= 2 && cross(h[h.size()-2], h[h.size()-1], pts[i]) <= 0)
            h.pop_back();
        h.push_back(pts[i]);
    }
    // 上凸壳
    int lower_size = (int)h.size();
    for (int i = n - 2; i >= 0; i--) {
        while ((int)h.size() > lower_size && cross(h[h.size()-2], h[h.size()-1], pts[i]) <= 0)
            h.pop_back();
        h.push_back(pts[i]);
    }
    h.pop_back(); // 去掉起点重复
    return h;
}

// =====================================================================================
// PCA 主轴对齐：让房间长边竖直
// =====================================================================================
static std::vector<Pt> alignToScreen(const std::vector<Pt>& pts) {
    int n = (int)pts.size();
    if (n < 2) return pts;

    double cx = 0, cz = 0;
    for (auto& p : pts) { cx += p.x; cz += p.z; }
    cx /= n; cz /= n;

    double cxx = 0, czz = 0, cxz = 0;
    for (auto& p : pts) {
        double dx = p.x - cx, dz = p.z - cz;
        cxx += dx * dx; czz += dz * dz; cxz += dx * dz;
    }

    double diff = (cxx - czz) / 2.0;
    double eigenAngle = 0.5 * std::atan2(2.0 * cxz, diff * 2.0 + 1e-10);
    double axisX = std::cos(eigenAngle);
    double axisZ = std::sin(eigenAngle);

    double angleToY = std::atan2(axisX, axisZ);
    double cosA = std::cos(-angleToY);
    double sinA = std::sin(-angleToY);

    std::vector<Pt> result;
    result.reserve(n);
    for (auto& p : pts) {
        double dx = p.x - cx, dz = p.z - cz;
        result.push_back({
            (float)(cx + dx * cosA - dz * sinA),
            (float)(cz + dx * sinA + dz * cosA)
        });
    }
    return result;
}

// =====================================================================================
// JNI 入口：extractBoundaryPolygon
// 输入：points = [x0,z0, x1,z1, ...], resolution = 栅格大小（默认 0.15m）
// 输出：[x0,z0, x1,z1, ...] 凸包顶点（PCA 对齐后）
// =====================================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_echo_MapProcessor_extractBoundaryPolygon(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray points,
        jfloat resolution)
{
    jint len = env->GetArrayLength(points);
    if (len < 6) return env->NewFloatArray(0); // 至少 3 个点

    jfloat* raw = env->GetFloatArrayElements(points, nullptr);

    int count = len / 2;

    // ── 1. 网格去重（0.1m 分辨率）─────────────────────────────────────────────
    const float DEDUP_RES = 0.1f;
    std::unordered_set<long long> dedupSet;
    std::vector<Pt> deduped;
    deduped.reserve(count);

    for (int i = 0; i < count; i++) {
        float x = raw[i * 2];
        float z = raw[i * 2 + 1];
        long long cx = (long long)std::floor(x / DEDUP_RES);
        long long cz = (long long)std::floor(z / DEDUP_RES);
        long long key = (cx << 32) | (cz & 0xFFFFFFFFL);
        if (dedupSet.insert(key).second) {
            deduped.push_back({x, z});
        }
    }

    env->ReleaseFloatArrayElements(points, raw, JNI_ABORT);

    if ((int)deduped.size() < 3) return env->NewFloatArray(0);

    // ── 2. 栅格化（resolution 分辨率）──────────────────────────────────────────
    float minX = deduped[0].x, minZ = deduped[0].z;
    for (auto& p : deduped) {
        if (p.x < minX) minX = p.x;
        if (p.z < minZ) minZ = p.z;
    }

    std::unordered_set<long long> occupied;
    for (auto& p : deduped) {
        int gx = (int)std::floor((p.x - minX) / resolution);
        int gz = (int)std::floor((p.z - minZ) / resolution);
        long long key = ((long long)gx << 32) | ((long long)gz & 0xFFFFFFFFL);
        occupied.insert(key);
    }

    // ── 3. 边缘格提取 ───────────────────────────────────────────────────────────
    const int dx8[] = {-1,-1,-1, 0, 0, 1, 1, 1};
    const int dz8[] = {-1, 0, 1,-1, 1,-1, 0, 1};

    std::vector<Pt> edgeCells;
    for (auto& key : occupied) {
        int gx = (int)(key >> 32);
        int gz = (int)(key & 0xFFFFFFFFL);
        bool isEdge = false;
        for (int d = 0; d < 8; d++) {
            int nx = gx + dx8[d], nz = gz + dz8[d];
            long long nk = ((long long)nx << 32) | ((long long)nz & 0xFFFFFFFFL);
            if (occupied.find(nk) == occupied.end()) { isEdge = true; break; }
        }
        if (isEdge) {
            float wx = minX + (gx + 0.5f) * resolution;
            float wz = minZ + (gz + 0.5f) * resolution;
            edgeCells.push_back({wx, wz});
        }
    }

    if ((int)edgeCells.size() < 3) return env->NewFloatArray(0);

    // ── 4. 凸包 ─────────────────────────────────────────────────────────────────
    std::vector<Pt> hull = convexHull(edgeCells);

    // ── 5. PCA 对齐 ──────────────────────────────────────────────────────────────
    std::vector<Pt> aligned = alignToScreen(hull);

    // ── 6. 打包返回 ──────────────────────────────────────────────────────────────
    int outCount = (int)aligned.size();
    jfloatArray result = env->NewFloatArray(outCount * 2);
    std::vector<float> buf(outCount * 2);
    for (int i = 0; i < outCount; i++) {
        buf[i * 2]     = aligned[i].x;
        buf[i * 2 + 1] = aligned[i].z;
    }
    env->SetFloatArrayRegion(result, 0, outCount * 2, buf.data());
    return result;
}

// =====================================================================================
// Moore Neighborhood Contour Tracing 辅助函数
// =====================================================================================

// 8 方向顺时针：E, SE, S, SW, W, NW, N, NE
static const int MOORE_DX[8] = { 1, 1, 0,-1,-1,-1, 0, 1};
static const int MOORE_DZ[8] = { 0, 1, 1, 1, 0,-1,-1,-1};

static bool isOcc(const std::unordered_set<long long>& occ, int gx, int gz) {
    return occ.count(((long long)gx << 32) | ((long long)gz & 0xFFFFFFFFL)) > 0;
}

// 追踪外轮廓，返回格子序列（含重复，用于连续追踪）
static std::vector<std::pair<int,int>> mooreTrace(
        const std::unordered_set<long long>& occ)
{
    if (occ.empty()) return {};
    // 找最左（x 最小，x 相同取 z 最小）格作为起点，保证是外轮廓上的点
    int sX = INT_MAX, sZ = INT_MAX;
    for (auto& k : occ) {
        int gx = (int)(k >> 32), gz = (int)(k & 0xFFFFFFFFL);
        if (gx < sX || (gx == sX && gz < sZ)) { sX = gx; sZ = gz; }
    }
    std::vector<std::pair<int,int>> result;
    result.push_back({sX, sZ});
    int cx = sX, cz = sZ;
    // 起点最左，其左侧（West=4方向）必为空，初始背景方向=West
    int backDir = 4;
    const int MAX_STEPS = 200000;
    for (int step = 0; step < MAX_STEPS; step++) {
        bool found = false;
        for (int i = 1; i <= 8; i++) {
            int dir = (backDir + i) % 8;
            int nx = cx + MOORE_DX[dir], nz = cz + MOORE_DZ[dir];
            if (isOcc(occ, nx, nz)) {
                backDir = (dir + 4) % 8; // 进入新格后，背景方向反转
                cx = nx; cz = nz;
                if (cx == sX && cz == sZ && (int)result.size() > 2) return result;
                result.push_back({cx, cz});
                found = true;
                break;
            }
        }
        if (!found) break; // 孤立格
    }
    return result;
}

// 顺序去重（保留第一次出现）
static std::vector<std::pair<int,int>> dedupContour(
        const std::vector<std::pair<int,int>>& raw)
{
    std::unordered_set<long long> seen;
    std::vector<std::pair<int,int>> out;
    for (auto& p : raw) {
        long long k = ((long long)p.first << 32) | ((long long)p.second & 0xFFFFFFFFL);
        if (seen.insert(k).second) out.push_back(p);
    }
    return out;
}

// =====================================================================================
// JNI 入口：extractConcaveHull
// 输入：points = [x0,z0, x1,z1, ...], resolution = 栅格大小（建议 0.15m）
// 输出：[x0,z0, x1,z1, ...] 外轮廓顶点（PCA 对齐后，保留凹形区域）
// =====================================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_echo_MapProcessor_extractConcaveHull(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray points,
        jfloat resolution)
{
    jint len = env->GetArrayLength(points);
    if (len < 6) return env->NewFloatArray(0);
    jfloat* raw = env->GetFloatArrayElements(points, nullptr);
    int count = len / 2;

    // ── 1. 网格去重（与主函数相同）────────────────────────────────────────────
    const float DEDUP_RES = 0.1f;
    std::unordered_set<long long> dedupSet;
    std::vector<Pt> deduped;
    deduped.reserve(count);
    for (int i = 0; i < count; i++) {
        float x = raw[i*2], z = raw[i*2+1];
        long long cx2 = (long long)std::floor(x / DEDUP_RES);
        long long cz2 = (long long)std::floor(z / DEDUP_RES);
        long long key = (cx2 << 32) | (cz2 & 0xFFFFFFFFL);
        if (dedupSet.insert(key).second) deduped.push_back({x, z});
    }
    env->ReleaseFloatArrayElements(points, raw, JNI_ABORT);
    if ((int)deduped.size() < 3) return env->NewFloatArray(0);

    // ── 2. 栅格化────────────────────────────────────────────────────────────
    float minX = deduped[0].x, minZ = deduped[0].z;
    for (auto& p : deduped) { minX = std::min(minX, p.x); minZ = std::min(minZ, p.z); }
    std::unordered_set<long long> occupied;
    for (auto& p : deduped) {
        int gx = (int)std::floor((p.x - minX) / resolution);
        int gz = (int)std::floor((p.z - minZ) / resolution);
        occupied.insert(((long long)gx << 32) | ((long long)gz & 0xFFFFFFFFL));
    }

    // ── 3. 形态学膨胀 1 格（填补稀疏点云在走廊内的小空洞，保证连通）──────────
    std::unordered_set<long long> expanded = occupied;
    for (auto& key : occupied) {
        int gx = (int)(key >> 32), gz = (int)(key & 0xFFFFFFFFL);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                expanded.insert(((long long)(gx+dx) << 32) | ((long long)(gz+dz) & 0xFFFFFFFFL));
    }

    // ── 4. Moore 轮廓追踪────────────────────────────────────────────────────
    auto raw2 = mooreTrace(expanded);
    auto contour = dedupContour(raw2);

    // ── 5. 轮廓点不足时降级为凸包──────────────────────────────────────────────
    if ((int)contour.size() < 3) {
        const int dx8[] = {-1,-1,-1, 0, 0, 1, 1, 1};
        const int dz8[] = {-1, 0, 1,-1, 1,-1, 0, 1};
        std::vector<Pt> edgeCells;
        for (auto& k : occupied) {
            int gx = (int)(k >> 32), gz = (int)(k & 0xFFFFFFFFL);
            bool isEdge = false;
            for (int d = 0; d < 8; d++) {
                if (!isOcc(occupied, gx+dx8[d], gz+dz8[d])) { isEdge = true; break; }
            }
            if (isEdge)
                edgeCells.push_back({minX+(gx+0.5f)*resolution, minZ+(gz+0.5f)*resolution});
        }
        std::vector<Pt> hull = convexHull(edgeCells);
        std::vector<Pt> aligned = alignToScreen(hull);
        int outN = (int)aligned.size();
        jfloatArray res = env->NewFloatArray(outN * 2);
        std::vector<float> buf(outN * 2);
        for (int i = 0; i < outN; i++) { buf[i*2] = aligned[i].x; buf[i*2+1] = aligned[i].z; }
        env->SetFloatArrayRegion(res, 0, outN*2, buf.data());
        return res;
    }

    // ── 6. 轮廓格 → 世界坐标──────────────────────────────────────────────────
    std::vector<Pt> pts;
    pts.reserve(contour.size());
    for (auto& p : contour)
        pts.push_back({minX + (p.first + 0.5f) * resolution,
                       minZ + (p.second + 0.5f) * resolution});

    // ── 7. 角度筛选：去除共线/近似共线的中间点（减少锯齿顶点）────────────────
    std::vector<Pt> simplified;
    simplified.push_back(pts[0]);
    for (int i = 1; i + 1 < (int)pts.size(); i++) {
        const Pt& prev = simplified.back();
        const Pt& curr = pts[i];
        const Pt& next = pts[i+1];
        float crossVal = (curr.x - prev.x) * (next.z - prev.z)
                       - (curr.z - prev.z) * (next.x - prev.x);
        if (std::abs(crossVal) > resolution * resolution * 0.25f)
            simplified.push_back(curr);
    }
    if (!pts.empty()) simplified.push_back(pts.back());
    if ((int)simplified.size() < 3) simplified = pts;

    // ── 8. PCA 对齐──────────────────────────────────────────────────────────
    std::vector<Pt> aligned = alignToScreen(simplified);

    // ── 9. 打包返回──────────────────────────────────────────────────────────
    int outCount = (int)aligned.size();
    jfloatArray result = env->NewFloatArray(outCount * 2);
    std::vector<float> buf(outCount * 2);
    for (int i = 0; i < outCount; i++) { buf[i*2] = aligned[i].x; buf[i*2+1] = aligned[i].z; }
    env->SetFloatArrayRegion(result, 0, outCount*2, buf.data());
    return result;
}

// =====================================================================================
// JNI 入口：extractObstacleGrid
// 输入：
//   points3d     = [x0,y0,z0, x1,y1,z1, ...] 3D 点云（含高度 y）
//   floorY       = 地面 Y 值
//   heightThresh = 障碍高度阈值（高于 floorY+heightThresh 的点算障碍，建议 0.3f）
//   resolution   = 网格分辨率（建议 0.15f）
// 输出：障碍物网格中心坐标 [x0,z0, x1,z1, ...]
//
// 策略：保守膨胀 + 密度过滤 + 连通域过滤
//   - 不规则障碍物边缘点落在格子边缘时，膨胀1格确保不漏标
//   - 密度过滤排除单点噪点膨胀产生的假阳性
//   - 连通域 < 3 格的孤立团直接丢弃
// =====================================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_echo_MapProcessor_extractObstacleGrid(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray points3d,
        jfloat floorY,
        jfloat heightThresh,
        jfloat resolution)
{
    jint len = env->GetArrayLength(points3d);
    if (len < 3) return env->NewFloatArray(0);
    jfloat* raw = env->GetFloatArrayElements(points3d, nullptr);
    int count = len / 3;

    // ── 1. 提取高于地面的点（同时过滤天花板，上限 floorY+2.5m）───────────────
    float minX = FLT_MAX, minZ = FLT_MAX;
    std::vector<Pt> abovePts;
    abovePts.reserve(count / 4);
    for (int i = 0; i < count; i++) {
        float x = raw[i*3], y = raw[i*3+1], z = raw[i*3+2];
        if (y > floorY + heightThresh && y < floorY + 2.5f) {
            abovePts.push_back({x, z});
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
        }
    }
    env->ReleaseFloatArrayElements(points3d, raw, JNI_ABORT);
    if (abovePts.empty()) return env->NewFloatArray(0);

    // ── 2. 统计每格原始点数────────────────────────────────────────────────────
    std::unordered_map<long long, int> cellCount;
    for (auto& p : abovePts) {
        int gx = (int)std::floor((p.x - minX) / resolution);
        int gz = (int)std::floor((p.z - minZ) / resolution);
        cellCount[((long long)gx << 32) | ((long long)gz & 0xFFFFFFFFL)]++;
    }

    // ── 3. 保守膨胀：每个有点的格向8邻扩散，贡献自身点数给邻格────────────────
    //   候选格 score = 膨胀邻域内所有原始点数之和
    //   效果：不规则障碍物边缘点落在格子边缘时，邻格也会得到足够的 score
    std::unordered_map<long long, int> candidateScore;
    for (auto& kv : cellCount) {
        int gx = (int)(kv.first >> 32), gz = (int)(kv.first & 0xFFFFFFFFL);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                long long nk = ((long long)(gx+dx) << 32) | ((long long)(gz+dz) & 0xFFFFFFFFL);
                candidateScore[nk] += kv.second;
            }
    }

    // ── 4. 密度过滤：score >= 2 才认为是真实障碍格（排除单点噪点膨胀）──────────
    const int MIN_SCORE = 2;
    std::unordered_set<long long> obstacleSet;
    for (auto& kv : candidateScore) {
        if (kv.second >= MIN_SCORE) obstacleSet.insert(kv.first);
    }

    // ── 5. 连通域过滤：连通格 < 3 的孤立团丢弃────────────────────────────────
    std::unordered_set<long long> visited;
    std::vector<Pt> result;
    for (auto& key : obstacleSet) {
        if (visited.count(key)) continue;
        std::queue<long long> q;
        q.push(key); visited.insert(key);
        std::vector<long long> comp;
        comp.push_back(key);
        while (!q.empty()) {
            long long cur = q.front(); q.pop();
            int cgx = (int)(cur >> 32), cgz = (int)(cur & 0xFFFFFFFFL);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long long nk = ((long long)(cgx+dx) << 32) | ((long long)(cgz+dz) & 0xFFFFFFFFL);
                    if (obstacleSet.count(nk) && !visited.count(nk)) {
                        visited.insert(nk); q.push(nk); comp.push_back(nk);
                    }
                }
        }
        if ((int)comp.size() < 3) continue; // 孤立小团丢弃
        for (auto& k : comp) {
            int gx = (int)(k >> 32), gz = (int)(k & 0xFFFFFFFFL);
            result.push_back({minX + (gx + 0.5f) * resolution,
                              minZ + (gz + 0.5f) * resolution});
        }
    }

    // ── 6. 打包返回──────────────────────────────────────────────────────────
    int outCount = (int)result.size();
    jfloatArray outArr = env->NewFloatArray(outCount * 2);
    std::vector<float> buf(outCount * 2);
    for (int i = 0; i < outCount; i++) { buf[i*2] = result[i].x; buf[i*2+1] = result[i].z; }
    env->SetFloatArrayRegion(outArr, 0, outCount*2, buf.data());
    return outArr;
}
