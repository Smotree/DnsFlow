package ru.dnsflow.app.data

import androidx.room.withTransaction

/** Applies places from other phones to a database. Separate from [Repo] so tests can use their own database. */
class PlaceStore(private val db: AppDb, private val localName: () -> String) {
    private val dao get() = db.dao()

    class Applied(
        /** Places that changed here (the host relays them to the other phones). */
        val changed: List<Place>,
        /** Places marked as a problem because of an EAN conflict; they go back to every phone. */
        val conflicts: List<Place>,
        val newEans: Set<String>,
        /** Order or numbers changed: the list needs [renumber]. */
        val reorder: Boolean = false,
    )

    /**
     * With [renumber] false the caller renumbers later (once after a burst of messages):
     * renumbering after every message of a 1000-place exchange was the slow part.
     */
    suspend fun applyRemote(
        sessionId: Long,
        remote: List<Place>,
        now: Long = System.currentTimeMillis(),
        renumber: Boolean = true,
    ): Applied {
        val changed = mutableListOf<Place>()
        val conflicts = mutableListOf<Place>()
        val newEans = mutableSetOf<String>()
        var reorder = false
        db.withTransaction {
            val byRaw = dao.placesWithDeleted(sessionId).associateBy { it.raw }
            for (r in remote) {
                val local = byRaw[r.raw]
                val out = Merge.merge(local, r, now, localName()) ?: continue
                val place = if (local == null) {
                    reorder = true
                    val p = out.place.copy(sessionId = sessionId, position = Int.MAX_VALUE)
                    p.copy(id = dao.insertPlace(p))
                } else {
                    if (out.place.scannedAt != local.scannedAt || out.place.deleted != local.deleted) reorder = true
                    out.place.also { dao.updatePlace(it) }
                }
                changed += place
                if (out.conflict) conflicts += place
                out.newEan?.let(newEans::add)
            }
            if (reorder && renumber) renumberInTransaction(sessionId)
        }
        return Applied(changed, conflicts, newEans, reorder)
    }

    suspend fun renumber(sessionId: Long) = db.withTransaction { renumberInTransaction(sessionId) }

    /** Numbers places by scan time so every phone shows the same order and numbers. */
    private suspend fun renumberInTransaction(sessionId: Long) {
        dao.placesOnce(sessionId)
            .sortedWith(compareBy({ it.scannedAt }, { it.raw }))
            .forEachIndexed { i, p -> if (p.position != i + 1) dao.updatePlace(p.copy(position = i + 1)) }
    }
}
