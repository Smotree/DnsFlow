package ru.dnsflow.app.sync

import org.json.JSONArray
import org.json.JSONObject
import ru.dnsflow.app.data.Place

/** Messages exchanged between phones (JSON). Pure so tests can run the same encoding. */
object Protocol {
    /** Places per message; Nearby byte payloads are limited to 32 KB. */
    const val CHUNK = 40

    fun places(uid: String, places: List<Place>, localName: String): JSONObject = JSONObject()
        .put("t", "places").put("uid", uid)
        .put("places", JSONArray().apply { places.forEach { put(placeToJson(it, localName)) } })

    fun readPlaces(msg: JSONObject): List<Place> {
        val arr = msg.getJSONArray("places")
        return (0 until arr.length()).map { placeFromJson(arr.getJSONObject(it)) }
    }

    fun placeToJson(p: Place, localName: String): JSONObject = JSONObject()
        .put("raw", p.raw).put("format", p.format)
        .put("ean", p.ean ?: JSONObject.NULL).put("eanFormat", p.eanFormat ?: JSONObject.NULL)
        .put("scannedAt", p.scannedAt).put("scannedBy", p.scannedBy.ifBlank { localName })
        .put("problem", p.problem).put("problemNote", p.problemNote).put("problemResolved", p.problemResolved)
        .put("verified", p.verified).put("updatedAt", p.updatedAt).put("deleted", p.deleted).put("part", p.part)
        .put("pieces", p.pieces).put("extraEans", p.extraEans)

    fun placeFromJson(o: JSONObject) = Place(
        sessionId = 0, position = 0,
        raw = o.getString("raw"), format = o.optString("format", "QR_CODE"),
        ean = o.str("ean"), eanFormat = o.str("eanFormat"),
        scannedAt = o.optLong("scannedAt"), scannedBy = o.optString("scannedBy"),
        problem = o.optBoolean("problem"), problemNote = o.optString("problemNote"),
        problemResolved = o.optBoolean("problemResolved"), verified = o.optBoolean("verified"),
        updatedAt = o.optLong("updatedAt"), deleted = o.optBoolean("deleted"), part = o.optInt("part"),
        pieces = o.optInt("pieces", 1).coerceAtLeast(1), extraEans = o.optString("extraEans"),
    )

    fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
