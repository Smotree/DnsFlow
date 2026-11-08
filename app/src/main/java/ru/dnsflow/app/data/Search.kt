package ru.dnsflow.app.data

/** Finding places in an acceptance by what is written on the box or shown in the app. */
object Search {
    private val SPACES = Regex("""\s+""")
    /**
     * Digits match the cargo-place number (any part of it, e.g. the last digits), the place's
     * position "№", an EAN (box or separate pieces) or the DNS product code; words match the
     * product name or the problem note. Case and spaces do not matter.
     */
    fun matches(place: Place, product: Product?, query: String): Boolean {
        val q = query.trim().lowercase().replace(SPACES, " ")
        if (q.isEmpty()) return true
        val digits = q.removePrefix("№").trim()
        if (digits.all { it.isDigit() }) {
            return digits == place.position.toString() ||
                Codes.parsePlace(place.raw).number.contains(digits) || place.raw.contains(digits) ||
                place.ean?.contains(digits) == true || place.extraEans.contains(digits) ||
                product?.code?.contains(digits) == true
        }
        return product?.name?.lowercase()?.replace(SPACES, " ")?.contains(q) == true ||
            place.problemNote.lowercase().contains(q)
    }
}
