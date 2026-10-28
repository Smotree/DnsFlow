package ru.dnsflow.app.recover

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Rebuilds a damaged DNS cargo-place QR when only the printed place number is known.
 *
 * The QR holds "mst" + DDMMYYYY + HHMMSS + 10-digit place number, encoded by the label printer
 * as a byte segment ("mst") plus a numeric segment (24 digits = 8 groups of 3 digits, 10 bits
 * each), version 2 (25x25), error correction M. The number fixes the last 3 groups; the date and
 * time (groups 0..4) are unknown. QR bits are linear in the data bits, so every group value has
 * a precomputed bit pattern and a candidate costs a few XORs + popcounts. That lets us test every
 * second of a months-long window and keep the candidate whose code best matches what survived.
 */
object QrRecovery {
    const val SIZE = 25
    private const val CELLS = SIZE * SIZE
    private const val WORDS = (CELLS + 63) / 64

    data class Candidate(val text: String, val printedAt: LocalDateTime, val mismatches: Int, val mask: Int)

    data class Result(
        /** Best first; distinct codes. */
        val candidates: List<Candidate>,
        val searched: Long,
        /** Cells that took part in the comparison (uncertain ones are left out). */
        val cells: Int = CELLS,
    ) {
        val best get() = candidates.firstOrNull()
        /** How many more cells the runner-up misses: the bigger, the surer the answer. */
        val margin get() = if (candidates.size > 1) candidates[1].mismatches - candidates[0].mismatches else cells
        /** Share of the code that matches the winner. */
        val match get() = best?.let { 1f - it.mismatches.toFloat() / cells } ?: 0f
        val confident get() = best != null && margin >= 10 && match >= 0.8f
    }

    /** 10-digit number as stored in the QR (the label prints 9 digits, dropping the leading 0). */
    fun qrNumber(printed: String): String? {
        val d = printed.filter { it.isDigit() }
        return when (d.length) {
            9 -> "0$d"
            10 -> d
            else -> null
        }
    }

    /** The code exactly as the label printer makes it, as SIZE*SIZE cells (true = dark). */
    fun encode(text: String, mask: Int): BooleanArray {
        val hints = mapOf(
            EncodeHintType.QR_COMPACT to "true", // byte "mst" + numeric digits, like the printer
            EncodeHintType.QR_MASK_PATTERN to mask,
            EncodeHintType.QR_VERSION to 2,
        )
        val m = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
        check(m.width == SIZE) { "unexpected QR size ${m.width}" }
        return BooleanArray(CELLS) { m.get(it % SIZE, it / SIZE).toInt() == 1 }
    }

    private fun pack(cells: BooleanArray): LongArray {
        val out = LongArray(WORDS)
        for (i in cells.indices) if (cells[i]) out[i ushr 6] = out[i ushr 6] or (1L shl (i and 63))
        return out
    }

    private fun xorInto(a: LongArray, b: LongArray) { for (i in a.indices) a[i] = a[i] xor b[i] }

    private fun text(groups: IntArray, tail: String) =
        "mst" + groups.joinToString("") { "%03d".format(it) } + tail.substring(1)

    /** Cells that depend only on the mask (format info, fixed header): used to tell which mask was used. */
    private val formatCells: List<Int> by lazy {
        // Several different codes: a data cell that matches by chance in two codes won't in all.
        val rnd = java.util.Random(42)
        val texts = List(8) { "mst" + (1..24).map { rnd.nextInt(10) }.joinToString("") }
        val codes = texts.map { t -> (0..7).map { encode(t, it) } }
        (0 until CELLS).filter { c ->
            (0..7).all { m -> codes.all { it[m][c] == codes[0][m][c] } } && (1..7).any { m -> codes[0][m][c] != codes[0][0][c] }
        }
    }

    /** Masks ordered by how well the observed format cells fit; a damaged format area keeps all masks. */
    fun likelyMasks(observed: BooleanArray): List<Int> {
        val ref = (0..7).map { encode("mst010120260000000000000001", it) }
        val scored = (0..7).map { m -> m to formatCells.count { ref[m][it] != observed[it] } }.sortedBy { it.second }
        val bestScore = scored.first().second
        // These cells (format info + the fixed "mst" header) differ a lot between masks, so the
        // right mask stands out even on a damaged code; keep every mask that is close to the best.
        return scored.filter { it.second <= bestScore + 12 }.map { it.first }
    }

    /**
     * Searches every second from [from] to [to] (whole days) for the printed number.
     * [observed] is the sampled grid of the damaged code, SIZE*SIZE cells, row by row.
     * [use] marks the cells to compare (null = all); cells the camera frames disagree on are
     * better left out than counted.
     */
    fun solve(
        observed: BooleanArray,
        printedNumber: String,
        from: LocalDate,
        to: LocalDate,
        use: BooleanArray? = null,
        masks: List<Int> = likelyMasks(observed),
        keep: Int = 5,
        isCancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): Result {
        require(observed.size == CELLS)
        val tail = qrNumber(printedNumber) ?: error("нужно 9 цифр номера")
        val tail0 = tail[0] - '0'
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val top = java.util.PriorityQueue<Candidate>(compareByDescending { it.mismatches })
        var searched = 0L
        val totalSteps = (masks.size * days.size).coerceAtLeast(1)
        val usePacked = pack(use ?: BooleanArray(CELLS) { true })
        val usedCells = usePacked.sumOf { java.lang.Long.bitCount(it) }
        var step = 0

        for (mask in masks) {
            val zero = IntArray(5)
            val base = pack(encode(text(zero, tail), mask))
            // tables[g][v] = cells flipped by group g having value v (bit patterns XOR together).
            val bitPatterns = Array(5) { g ->
                Array(10) { bit ->
                    val gs = IntArray(5).also { it[g] = 1 shl bit }
                    pack(encode(text(gs, tail), mask)).also { xorInto(it, base) }
                }
            }
            val tables = Array(5) { g ->
                Array(1000) { v ->
                    val acc = LongArray(WORDS)
                    for (bit in 0 until 10) if ((v shr bit) and 1 == 1) xorInto(acc, bitPatterns[g][bit])
                    acc
                }
            }
            val target = pack(observed).also { xorInto(it, base) }
            val acc1 = LongArray(WORDS); val acc2 = LongArray(WORDS); val acc3 = LongArray(WORDS)
            val t4 = Array(60) { s -> LongArray(WORDS) { i -> tables[4][s * 10 + tail0][i] and usePacked[i] } }

            for (day in days) {
                if (isCancelled()) return Result(top.sortedBy { it.mismatches }, searched, usedCells)
                // DDMMYYYY HHMMSS -> groups DDM | MYY | YYH | HMM | SS+number[0]
                val dd = day.dayOfMonth; val mo = day.monthValue; val y = day.year
                val g0 = dd * 10 + mo / 10
                val g1 = (mo % 10) * 100 + (y / 100)
                for (i in 0 until WORDS) acc1[i] = (target[i] xor tables[0][g0][i] xor tables[1][g1][i]) and usePacked[i]
                for (h in 0 until 24) {
                    val g2 = (y % 100) * 10 + h / 10
                    for (i in 0 until WORDS) acc2[i] = acc1[i] xor (tables[2][g2][i] and usePacked[i])
                    for (mi in 0 until 60) {
                        val g3 = (h % 10) * 100 + mi
                        val t3 = tables[3][g3]
                        for (i in 0 until WORDS) acc3[i] = acc2[i] xor (t3[i] and usePacked[i])
                        for (s in 0 until 60) {
                            val t = t4[s]
                            var d = 0
                            for (i in 0 until WORDS) d += java.lang.Long.bitCount(acc3[i] xor t[i])
                            if (top.size < keep || d < top.peek()!!.mismatches) {
                                val at = day.atTime(h, mi, s)
                                val code = "mst%02d%02d%04d%02d%02d%02d%s".format(dd, mo, y, h, mi, s, tail)
                                top.add(Candidate(code, at, d, mask))
                                if (top.size > keep) top.poll()
                            }
                        }
                        searched += 60
                    }
                }
                progress(++step / totalSteps.toFloat())
            }
        }
        return Result(top.sortedBy { it.mismatches }.distinctBy { it.text }, searched, usedCells)
    }
}
