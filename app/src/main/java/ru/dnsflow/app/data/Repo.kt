package ru.dnsflow.app.data

import androidx.room.withTransaction
import ru.dnsflow.app.App
import ru.dnsflow.app.sync.Sync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Write operations shared by the screens. Every change is also pushed to connected phones. */
object Repo {
    private val app get() = App.instance
    private val dao get() = app.dao
    private fun now() = System.currentTimeMillis()

    suspend fun newSession(): Long {
        val now = now()
        val title = "Приёмка " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru")).format(Date(now))
        return dao.insertSession(Session(title = title, createdAt = now, uid = UUID.randomUUID().toString()))
    }

    sealed interface AddResult {
        data class Added(val place: Place) : AddResult
        data class Duplicate(val place: Place) : AddResult
    }

    suspend fun addPlace(sessionId: Long, raw: String, format: String): AddResult {
        val existing = dao.findPlace(sessionId, raw)
        if (existing != null && !existing.deleted) return AddResult.Duplicate(existing)
        val fresh = Place(
            id = existing?.id ?: 0,
            sessionId = sessionId,
            position = dao.maxPosition(sessionId) + 1,
            raw = raw,
            format = format,
            scannedAt = now(),
            updatedAt = now(),
        )
        val place = if (existing != null) fresh.also { dao.updatePlace(it) } else fresh.copy(id = dao.insertPlace(fresh))
        Sync.publish(sessionId, listOf(place))
        return AddResult.Added(place)
    }

    private suspend fun change(placeId: Long, block: (Place) -> Place): Place? {
        val p = dao.place(placeId) ?: return null
        val updated = block(p).copy(updatedAt = now())
        dao.updatePlace(updated)
        Sync.publish(p.sessionId, listOf(updated))
        return updated
    }

    suspend fun attachEan(placeId: Long, ean: String?, format: String?): Place? {
        val updated = change(placeId) { it.copy(ean = ean, eanFormat = format) }
        if (ean != null) app.lookup.request(ean)
        return updated
    }

    suspend fun setProblem(placeId: Long, problem: Boolean, note: String? = null) {
        change(placeId) { it.copy(problem = problem, problemNote = note ?: it.problemNote, problemResolved = false) }
    }

    suspend fun setResolved(placeId: Long, resolved: Boolean) {
        change(placeId) { it.copy(problemResolved = resolved) }
    }

    /** "Shown in 1C" only ever goes from false to true, so it merges by OR and keeps updatedAt. */
    suspend fun setVerified(placeId: Long) {
        val p = dao.place(placeId) ?: return
        if (p.verified) return
        val updated = p.copy(verified = true)
        dao.updatePlace(updated)
        Sync.publish(p.sessionId, listOf(updated))
    }

    /** One more separate piece under the same cargo-place label (an item sent apart from the box). */
    suspend fun addPiece(placeId: Long) {
        change(placeId) { it.copy(pieces = it.pieces + 1) }
    }

    /** One piece less; drops the last separate EAN if there are more EANs than separate pieces. */
    suspend fun removePiece(placeId: Long) {
        change(placeId) {
            val pieces = (it.pieces - 1).coerceAtLeast(1)
            val eans = it.extraEans.lines().filter { e -> e.isNotBlank() }
            it.copy(pieces = pieces, extraEans = eans.take(pieces - 1).joinToString("\n"))
        }
    }

    /** EAN of a separate piece. */
    suspend fun addExtraEan(placeId: Long, ean: String) {
        change(placeId) { p ->
            p.copy(extraEans = (p.extraEans.lines().filter { it.isNotBlank() } + ean).joinToString("\n"))
        }
        app.lookup.request(ean)
    }

    /** Photos stay on this phone: not sent to other phones, so no sync timestamp change. */
    suspend fun addPhoto(placeId: Long, name: String) {
        val p = dao.place(placeId) ?: return
        dao.updatePlace(p.copy(photos = (p.photos.lines().filter { it.isNotBlank() } + name).joinToString("\n")))
    }

    suspend fun removePhoto(placeId: Long, name: String) {
        val p = dao.place(placeId) ?: return
        dao.updatePlace(p.copy(photos = p.photos.lines().filter { it.isNotBlank() && it != name }.joinToString("\n")))
        java.io.File(java.io.File(app.filesDir, "photos"), name).delete()
    }

    suspend fun deletePlace(placeId: Long) {
        val p = change(placeId) { it.copy(deleted = true) } ?: return
        renumber(p.sessionId)
    }

    private val store get() = PlaceStore(app.db) { app.prefs.deviceName }

    /** Applies places received from another phone (rules in [Merge]). */
    suspend fun applyRemote(sessionId: Long, remote: List<Place>, renumber: Boolean = true): PlaceStore.Applied {
        val res = store.applyRemote(sessionId, remote, renumber = renumber)
        res.newEans.forEach { app.lookup.request(it) }
        return res
    }

    suspend fun renumber(sessionId: Long) = store.renumber(sessionId)

    /** Splits the 1C check into [parts] equal consecutive ranges and pushes it to the other phones. */
    suspend fun split(sessionId: Long, parts: Int) {
        val places = dao.placesOnce(sessionId)
        val updated = places.mapIndexed { i, p ->
            val part = if (parts <= 1) 0 else i * parts / places.size + 1
            p.copy(part = part, updatedAt = now())
        }
        app.db.withTransaction { updated.forEach { dao.updatePlace(it) } }
        dao.session(sessionId)?.let { dao.updateSession(it.copy(splitParts = if (parts <= 1) 0 else parts)) }
        Sync.publish(sessionId, updated)
    }

    suspend fun setMyPart(sessionId: Long, parts: Int, part: Int) {
        dao.session(sessionId)?.let { dao.updateSession(it.copy(splitParts = parts, myPart = part)) }
    }

    suspend fun finishSession(id: Long) {
        dao.session(id)?.let { if (it.finishedAt == null) dao.updateSession(it.copy(finishedAt = now())) }
    }

    suspend fun reopenSession(id: Long) {
        dao.session(id)?.let { dao.updateSession(it.copy(finishedAt = null)) }
    }

    suspend fun renameSession(id: Long, title: String) {
        dao.session(id)?.let { dao.updateSession(it.copy(title = title)) }
    }

    suspend fun deleteSession(id: Long) {
        val photos = java.io.File(app.filesDir, "photos")
        dao.placesWithDeleted(id).flatMap { it.photos.lines() }.filter { it.isNotBlank() }.forEach { java.io.File(photos, it).delete() }
        dao.deletePlacesOf(id)
        dao.deleteSessionRow(id)
    }
}
