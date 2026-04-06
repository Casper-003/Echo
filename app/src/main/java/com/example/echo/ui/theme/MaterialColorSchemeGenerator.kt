package com.example.echo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.*

/**
 * 基于 HCT / LCh(ab) 色彩空间的 M3 Tonal Palette 生成器。
 *
 * 遵循 Material Design 3 色彩规范：
 *   - Tone = CIE L*（感知亮度 0–100）
 *   - Hue / Chroma = CIELAB 极坐标（LCh），与 M3 HCT 在视觉上高度吻合
 *   - secondary：与 seed 同色相，但 chroma 缩小为 1/3
 *   - tertiary：色相旋转 +60°，chroma 缩小为 1/2
 *   - neutral / neutralVariant：seed 色相，极低 chroma（微染色灰）
 *
 * 参考实现：https://github.com/material-foundation/material-color-utilities
 */
object MaterialColorSchemeGenerator {

    // ─── 对外 API ─────────────────────────────────

    fun lightScheme(seed: Color): ColorScheme {
        val lch = toLch(seed)
        val primary    = Palette(lch.h, lch.c)
        val secondary  = Palette(lch.h, (lch.c / 3.0).coerceAtLeast(6.0))
        val tertiary   = Palette((lch.h + 60.0).mod(360.0), (lch.c / 2.0).coerceAtLeast(6.0))
        val neutral    = Palette(lch.h, 4.0)
        val neutralVar = Palette(lch.h, 8.0)
        val error      = Palette(25.0, 84.0)

        return lightColorScheme(
            primary              = primary(40),
            onPrimary            = primary(100),
            primaryContainer     = primary(90),
            onPrimaryContainer   = primary(10),
            secondary            = secondary(40),
            onSecondary          = secondary(100),
            secondaryContainer   = secondary(90),
            onSecondaryContainer = secondary(10),
            tertiary             = tertiary(40),
            onTertiary           = tertiary(100),
            tertiaryContainer    = tertiary(90),
            onTertiaryContainer  = tertiary(10),
            error                = error(40),
            onError              = error(100),
            errorContainer       = error(90),
            onErrorContainer     = error(10),
            background           = neutral(98),
            onBackground         = neutral(10),
            surface              = neutral(98),
            onSurface            = neutral(10),
            surfaceVariant       = neutralVar(90),
            onSurfaceVariant     = neutralVar(30),
            surfaceContainer     = neutral(94),
            surfaceContainerHigh = neutral(92),
            surfaceContainerLow  = neutral(96),
            outline              = neutralVar(50),
            outlineVariant       = neutralVar(80),
            inverseSurface       = neutral(20),
            inverseOnSurface     = neutral(95),
            inversePrimary       = primary(80),
            scrim                = neutral(0),
        )
    }

    fun darkScheme(seed: Color): ColorScheme {
        val lch = toLch(seed)
        val primary    = Palette(lch.h, lch.c)
        val secondary  = Palette(lch.h, (lch.c / 3.0).coerceAtLeast(6.0))
        val tertiary   = Palette((lch.h + 60.0).mod(360.0), (lch.c / 2.0).coerceAtLeast(6.0))
        val neutral    = Palette(lch.h, 4.0)
        val neutralVar = Palette(lch.h, 8.0)
        val error      = Palette(25.0, 84.0)

        return darkColorScheme(
            primary              = primary(80),
            onPrimary            = primary(20),
            primaryContainer     = primary(30),
            onPrimaryContainer   = primary(90),
            secondary            = secondary(80),
            onSecondary          = secondary(20),
            secondaryContainer   = secondary(30),
            onSecondaryContainer = secondary(90),
            tertiary             = tertiary(80),
            onTertiary           = tertiary(20),
            tertiaryContainer    = tertiary(30),
            onTertiaryContainer  = tertiary(90),
            error                = error(80),
            onError              = error(20),
            errorContainer       = error(30),
            onErrorContainer     = error(90),
            background           = neutral(6),
            onBackground         = neutral(90),
            surface              = neutral(6),
            onSurface            = neutral(90),
            surfaceVariant       = neutralVar(30),
            onSurfaceVariant     = neutralVar(80),
            surfaceContainer     = neutral(12),
            surfaceContainerHigh = neutral(17),
            surfaceContainerLow  = neutral(10),
            outline              = neutralVar(60),
            outlineVariant       = neutralVar(30),
            inverseSurface       = neutral(90),
            inverseOnSurface     = neutral(20),
            inversePrimary       = primary(40),
            scrim                = neutral(0),
        )
    }

    // ─── Tonal Palette ────────────────────────────

    /** 固定色相/饱和度，按 tone(L*) 生成颜色 */
    private class Palette(val h: Double, val c: Double) {
        operator fun invoke(tone: Int): Color = lchToColor(tone.toDouble(), c, h)
    }

    // ─── LCh(ab) ↔ Color 转换 ─────────────────────

    private data class Lch(val l: Double, val c: Double, val h: Double)

    private fun toLch(color: Color): Lch {
        // Compose Color.value 是 SRGB packed (A16R16G16B16 in [0,1])
        val r = color.red.toDouble()
        val g = color.green.toDouble()
        val b = color.blue.toDouble()
        val (x, y, z) = rgbToXyz(r, g, b)
        val (l, a, bLab) = xyzToLab(x, y, z)
        val c = sqrt(a * a + bLab * bLab)
        val h = Math.toDegrees(atan2(bLab, a)).let { if (it < 0) it + 360.0 else it }
        return Lch(l, c, h)
    }

    private fun lchToColor(l: Double, c: Double, h: Double): Color {
        val hRad = Math.toRadians(h)
        val a = c * cos(hRad)
        val b = c * sin(hRad)
        val (x, y, z) = labToXyz(l, a, b)
        val (r, g, bVal) = xyzToRgb(x, y, z)
        return Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), bVal.toFloat().coerceIn(0f, 1f))
    }

    // ─── sRGB ↔ XYZ (D65) ────────────────────────

    private fun rgbToXyz(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val rl = linearize(r); val gl = linearize(g); val bl = linearize(b)
        return Triple(
            rl * 0.4124564 + gl * 0.3575761 + bl * 0.1804375,
            rl * 0.2126729 + gl * 0.7151522 + bl * 0.0721750,
            rl * 0.0193339 + gl * 0.1191920 + bl * 0.9503041
        )
    }

    private fun xyzToRgb(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val r = delinearize( x *  3.2404542 + y * -1.5371385 + z * -0.4985314)
        val g = delinearize( x * -0.9692660 + y *  1.8760108 + z *  0.0415560)
        val b = delinearize( x *  0.0556434 + y * -0.2040259 + z *  1.0572252)
        return Triple(r, g, b)
    }

    private fun linearize(c: Double) =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun delinearize(c: Double) =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    // ─── XYZ ↔ CIELAB (D65) ──────────────────────

    private val XN = 0.95047; private val YN = 1.00000; private val ZN = 1.08883

    private fun xyzToLab(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val fx = f(x / XN); val fy = f(y / YN); val fz = f(z / ZN)
        return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labToXyz(l: Double, a: Double, b: Double): Triple<Double, Double, Double> {
        val fy = (l + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        return Triple(fInv(fx) * XN, fInv(fy) * YN, fInv(fz) * ZN)
    }

    private fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
    private fun fInv(t: Double) = if (t > 0.20690) t.pow(3.0) else (t - 16.0 / 116.0) / 7.787

    private fun Double.pow(n: Double) = Math.pow(this, n)
    private fun Double.mod(m: Double) = ((this % m) + m) % m
}
