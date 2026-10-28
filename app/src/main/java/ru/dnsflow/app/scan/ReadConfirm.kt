package ru.dnsflow.app.scan

import ru.dnsflow.app.data.Codes

/**
 * A product barcode is accepted only after the camera reads the same digits in several frames.
 * ML Kit now and then misreads an EAN in a single frame — half of the code shifted by one digit
 * that still passes the check digit, or a short code "seen" inside a serial-number barcode.
 * A real code repeats from frame to frame, a misread does not: a value is taken only when it was
 * read [need] more times than any other product code in the last [windowMs].
 * Other codes (the cargo-place QR has its own error correction) are accepted at once.
 */
class ReadConfirm(private val windowMs: Long = 1500) {
    private val reads = ArrayDeque<Pair<Long, String>>()

    /** Feeds the code picked from one frame; true when it is confirmed and may be reported. */
    fun feed(now: Long, raw: String, format: String): Boolean {
        if (!Codes.isProduct(format)) {
            reads.clear()
            return true
        }
        reads.addLast(now to raw)
        while (reads.first().first < now - windowMs) reads.removeFirst()
        val votes = reads.groupingBy { it.second }.eachCount()
        val rival = votes.filterKeys { it != raw }.values.maxOrNull() ?: 0
        if (votes.getValue(raw) - rival < need(raw, format)) return false
        reads.clear()
        return true
    }

    /** A plain EAN-13 needs 3 matching frames; rarer kinds (EAN-8, UPC) need 4. */
    private fun need(raw: String, format: String) = if (format == "EAN_13" && !raw.startsWith("0")) 3 else 4
}
