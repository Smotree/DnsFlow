package ru.dnsflow.app.recover

import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.detector.Detector
import com.google.zxing.common.GridSampler as ZxingGridSampler

/**
 * Finds a QR in a camera frame and reads its cells even when the code can no longer be decoded.
 * Only the three big corner squares are needed to lay the grid. zxing's own grid also relies on
 * the small alignment square, which on a damaged or small code is often found in the wrong place
 * and skews the grid, so a second grid is laid from the corner squares alone. Every grid
 * is checked against the parts every QR of this size has (corner squares, timing lines) and the
 * best one wins; several crops and binarizers are tried.
 */
object GridSampler {
    /** Luminance (Y plane) of a frame, [width] x [height], one byte per pixel. */
    class Frame(val y: ByteArray, val width: Int, val height: Int)

    class Grid(val cells: BooleanArray, /** Share of the fixed QR parts that look right (0..1). */ val quality: Float)

    private const val N = QrRecovery.SIZE

    /** Cells that are the same in every code of this size (finders, separators, timing, alignment). */
    private val fixed: List<Pair<Int, Boolean>> by lazy {
        val rnd = java.util.Random(7)
        val codes = (0 until 16).map { i -> QrRecovery.encode("mst" + (1..24).map { rnd.nextInt(10) }.joinToString(""), i % 8) }
        (0 until N * N).filter { c -> codes.all { it[c] == codes[0][c] } }.map { it to codes[0][it] }
    }

    fun quality(cells: BooleanArray): Float = fixed.count { (c, v) -> cells[c] == v }.toFloat() / fixed.size

    /** Best grid found in the frame, or null. [aim] = share of the frame (centred) where the user aims. */
    fun sample(frame: Frame, aim: Float = 0.6f): Grid? {
        val full = PlanarYUVLuminanceSource(frame.y, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
        val crops = buildList<LuminanceSource> {
            for (share in listOf(aim, 1f, 0.8f, 0.4f)) {
                val w = (frame.width * share).toInt(); val h = (frame.height * share).toInt()
                add(full.crop((frame.width - w) / 2, (frame.height - h) / 2, w, h))
            }
        }
        var best: Grid? = null
        for (src in crops) {
            for (binarizer in listOf<(LuminanceSource) -> Binarizer>({ HybridBinarizer(it) }, { GlobalHistogramBinarizer(it) })) {
                val matrix = runCatching { BinaryBitmap(binarizer(src)).blackMatrix }.getOrNull() ?: continue
                for (g in grids(matrix)) {
                    if (best == null || g.quality > best.quality) best = g
                    if (g.quality >= 0.98f) return g
                }
            }
        }
        return best?.takeIf { it.quality >= 0.85f }
    }

    private fun grids(matrix: BitMatrix): List<Grid> {
        val out = mutableListOf<Grid>()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val res = runCatching { Detector(matrix).detect(hints) }.getOrNull() ?: return out
        // zxing's grid (uses the alignment square when it finds one): better on tilted photos.
        if (res.bits.width == N) out += grid(res.bits)
        // Grid from the three corner squares only (bottom-left, top-left, top-right centres).
        val p = res.points
        if (p.size >= 3) runCatching {
            val bl = p[0]; val tl = p[1]; val tr = p[2]
            val far = N - 3.5f
            val t = PerspectiveTransform.quadrilateralToQuadrilateral(
                3.5f, 3.5f, far, 3.5f, far, far, 3.5f, far,
                tl.x, tl.y, tr.x, tr.y, tr.x - tl.x + bl.x, tr.y - tl.y + bl.y, bl.x, bl.y,
            )
            out += grid(ZxingGridSampler.getInstance().sampleGrid(matrix, N, N, t))
        }
        return out
    }

    private fun grid(bits: BitMatrix): Grid {
        val cells = BooleanArray(N * N) { bits.get(it % N, it / N) }
        return Grid(cells, quality(cells))
    }
}
