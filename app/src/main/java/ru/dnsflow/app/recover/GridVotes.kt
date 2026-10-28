package ru.dnsflow.app.recover

/**
 * Grids of the same damaged QR from many camera frames. Each cell is decided by majority, and
 * cells the frames disagree on (glare, blur, the damage itself) are marked uncertain so the
 * search ignores them. One blurry frame no longer decides the result.
 */
class GridVotes(private val keep: Int = 12) {
    private val grids = ArrayDeque<GridSampler.Grid>()

    val count get() = grids.size
    val bestQuality get() = grids.maxOfOrNull { it.quality } ?: 0f

    fun add(grid: GridSampler.Grid) {
        grids.addLast(grid)
        // Keep the best recent frames.
        if (grids.size > keep) grids.remove(grids.minBy { it.quality })
    }

    fun clear() = grids.clear()

    class Vote(val cells: BooleanArray, val use: BooleanArray, val frames: Int, val certain: Float)

    fun vote(minAgreement: Float = 0.7f): Vote? {
        if (grids.isEmpty()) return null
        val n = QrRecovery.SIZE * QrRecovery.SIZE
        val dark = IntArray(n)
        for (g in grids) for (i in 0 until n) if (g.cells[i]) dark[i]++
        val total = grids.size
        val cells = BooleanArray(n) { dark[it] * 2 > total }
        val use = BooleanArray(n) { i ->
            val agree = maxOf(dark[i], total - dark[i]).toFloat() / total
            total < 3 || agree >= minAgreement
        }
        return Vote(cells, use, total, use.count { it }.toFloat() / n)
    }
}
