package ru.dnsflow.app.data

import com.google.mlkit.vision.barcode.common.Barcode

/** Helpers for the codes printed on DNS cargo labels and product boxes. */
object Codes {
    // mst + DDMMYYYY + HHMMSS + 10-digit cargo place number,
    // e.g. mst121020261810460000581000 -> 12.10.2026 18:10:46, place 000581000.
    private val MST = Regex("^mst(\\d{2})(\\d{2})(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{10})$")

    private val PRODUCT_FORMATS = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E")

    data class PlaceInfo(val number: String, val printedAt: String?)

    fun parsePlace(raw: String): PlaceInfo {
        val m = MST.matchEntire(raw) ?: return PlaceInfo(raw, null)
        val g = m.groupValues
        // The label prints the number with 9 digits; the QR pads it to 10.
        val number = g[7].let { if (it.startsWith('0')) it.drop(1) else it }
        return PlaceInfo(number, "${g[1]}.${g[2]}.${g[3]} ${g[4]}:${g[5]}:${g[6]}")
    }

    fun isProduct(format: String): Boolean = format in PRODUCT_FORMATS

    /** Cargo-place code: the QR "mst…" of new labels or the 9–10 digit barcode of old ones. */
    fun isPlaceCode(raw: String, format: String): Boolean =
        raw.startsWith("mst") ||
            (format != "QR_CODE" && !isProduct(format) && raw.length in 9..10 && raw.all { it.isDigit() })

    /**
     * Picks the code that matters from everything the camera sees at once. A product box carries
     * many codes (serial number, part number, maker's QR, EAN, UPC); only the cargo-place code and
     * the product EAN count. While waiting for the EAN the EAN wins, otherwise the place code.
     * Codes that are neither are ignored, so a serial number is never taken for a cargo place.
     * UPC-E is ignored too: goods here never carry it, but ML Kit "finds" one inside other barcodes.
     */
    fun pick(codes: List<Pair<String, String>>, waitingForEan: Boolean): Pair<String, String>? {
        val place = codes.firstOrNull { (raw, fmt) -> isPlaceCode(raw, fmt) }
        val product = codes.filter { isProduct(it.second) && it.second != "UPC_E" }
            .minByOrNull { (raw, fmt) -> productRank(raw, fmt) }
        return if (waitingForEan) product ?: place else place ?: product
    }

    /**
     * Real EAN-13 first. An EAN-13 starting with 0 is usually the US UPC read as EAN-13 (boxes
     * often carry both), so it only counts when there is nothing better.
     */
    private fun productRank(raw: String, format: String): Int = when {
        format == "EAN_13" && !raw.startsWith("0") -> 0
        format == "EAN_8" -> 1
        format == "EAN_13" -> 2
        else -> 3
    }

    /** Maps an ML Kit format constant to a zxing BarcodeFormat name. */
    fun formatName(mlkit: Int): String = when (mlkit) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        Barcode.FORMAT_PDF417 -> "PDF_417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "CODE_128"
    }

    fun label(format: String): String = when (format) {
        "QR_CODE" -> "QR"
        "DATA_MATRIX" -> "DataMatrix"
        "EAN_13" -> "EAN-13"
        "EAN_8" -> "EAN-8"
        "UPC_A" -> "UPC-A"
        "UPC_E" -> "UPC-E"
        else -> format.replace('_', '-')
    }
}
