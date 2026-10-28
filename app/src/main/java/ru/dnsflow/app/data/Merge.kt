package ru.dnsflow.app.data

/**
 * How a place received from another phone combines with the local copy. Pure so it can be
 * tested without phones; every phone applies the same rules, so all copies converge:
 * - fields edited on both sides: the later edit wins (ties broken by content, not by phone);
 * - "shown in 1C": once true on any phone, true everywhere;
 * - scan time: the earliest scan, with who made it;
 * - two different EANs for one place: the later one is kept, but the place is marked as a
 *   problem with both values so someone checks the box.
 */
object Merge {
    data class Outcome(
        val place: Place,
        /** The place was marked as a problem here and must be sent back to every phone. */
        val conflict: Boolean,
        /** EAN that is new on this phone and needs a product lookup. */
        val newEan: String?,
    )

    /** Returns null when the remote copy brings nothing new. */
    fun merge(local: Place?, remote: Place, now: Long, localName: String): Outcome? {
        if (local == null) {
            return Outcome(remote.copy(id = 0), conflict = false, newEan = remote.ean.takeIf { !remote.deleted })
        }
        var m = local
        var conflict = false
        var newEan: String? = null
        if (isNewer(remote, local)) {
            m = m.copy(
                ean = remote.ean, eanFormat = remote.eanFormat,
                problem = remote.problem, problemNote = remote.problemNote, problemResolved = remote.problemResolved,
                deleted = remote.deleted, part = remote.part, updatedAt = remote.updatedAt,
                pieces = remote.pieces, extraEans = remote.extraEans,
            )
            if (remote.ean != null && remote.ean != local.ean) newEan = remote.ean
            if (!local.deleted && !remote.deleted && local.ean != null && remote.ean != null && local.ean != remote.ean &&
                !remote.problemNote.contains(local.ean)
            ) {
                conflict = true
                m = m.copy(
                    problem = true,
                    problemResolved = false,
                    problemNote = joinNotes(
                        m.problemNote,
                        "EAN различается: ${local.ean} (${local.scannedBy.ifBlank { localName }}) и ${remote.ean} — оставлен ${remote.ean}",
                    ),
                    updatedAt = maxOf(now, remote.updatedAt + 1),
                )
            }
        }
        if (remote.scannedAt < m.scannedAt) m = m.copy(scannedAt = remote.scannedAt, scannedBy = remote.scannedBy)
        if (remote.verified && !m.verified) m = m.copy(verified = true)
        return if (m == local) null else Outcome(m, conflict, newEan)
    }

    private fun isNewer(remote: Place, local: Place): Boolean = when {
        remote.updatedAt != local.updatedAt -> remote.updatedAt > local.updatedAt
        else -> contentKey(remote) > contentKey(local)
    }

    private fun contentKey(p: Place) =
        listOf(p.ean, p.problem, p.problemNote, p.problemResolved, p.deleted, p.part, p.pieces, p.extraEans).joinToString("|")

    fun joinNotes(a: String, b: String): String = when {
        b.isBlank() || a.contains(b) -> a
        a.isBlank() -> b
        else -> "$a; $b"
    }
}
