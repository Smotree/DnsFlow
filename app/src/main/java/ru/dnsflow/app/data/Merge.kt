package ru.dnsflow.app.data

/**
 * How a place received from another phone combines with the local copy. Pure so it can be
 * tested without phones; every phone applies the same rules, so all copies converge:
 * - each group of fields (EAN, problem, 1C part, separate pieces, deleted) merges on its own:
 *   the later edit of that group wins (ties broken by content, not by phone). A phone holding a
 *   stale copy that edits one group can no longer wipe another group edited elsewhere;
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

    enum class Group { EAN, PROBLEM, PART, PIECES, DELETED }

    /** When [g] was last edited; places from phones before 2.2 only know [Place.updatedAt]. */
    fun Place.editedAt(g: Group): Long = when (g) {
        Group.EAN -> eanAt
        Group.PROBLEM -> problemAt
        Group.PART -> partAt
        Group.PIECES -> piecesAt
        Group.DELETED -> deletedAt
    }.takeIf { it > 0 } ?: updatedAt

    /**
     * A local edit of [g] at [at]. Groups without their own time first get the old [updatedAt],
     * so raising updatedAt here does not make them look newly edited too.
     */
    fun Place.stamp(g: Group, at: Long): Place = copy(
        eanAt = editedAt(Group.EAN), problemAt = editedAt(Group.PROBLEM), partAt = editedAt(Group.PART),
        piecesAt = editedAt(Group.PIECES), deletedAt = editedAt(Group.DELETED),
    ).stampOnly(g, at)

    private fun Place.stampOnly(g: Group, at: Long): Place = when (g) {
        Group.EAN -> copy(eanAt = at)
        Group.PROBLEM -> copy(problemAt = at)
        Group.PART -> copy(partAt = at)
        Group.PIECES -> copy(piecesAt = at)
        Group.DELETED -> copy(deletedAt = at)
    }.copy(updatedAt = maxOf(updatedAt, at))

    private fun take(m: Place, r: Place, g: Group): Place = when (g) {
        Group.EAN -> m.copy(ean = r.ean, eanFormat = r.eanFormat)
        Group.PROBLEM -> m.copy(problem = r.problem, problemNote = r.problemNote, problemResolved = r.problemResolved)
        Group.PART -> m.copy(part = r.part)
        Group.PIECES -> m.copy(pieces = r.pieces, extraEans = r.extraEans)
        Group.DELETED -> m.copy(deleted = r.deleted)
    }.stamp(g, r.editedAt(g))

    private fun content(p: Place, g: Group): String = when (g) {
        Group.EAN -> "${p.ean}|${p.eanFormat}"
        Group.PROBLEM -> "${p.problem}|${p.problemNote}|${p.problemResolved}"
        Group.PART -> "${p.part}"
        Group.PIECES -> "${p.pieces}|${p.extraEans}"
        Group.DELETED -> "${p.deleted}"
    }

    private fun remoteWins(remote: Place, local: Place, g: Group): Boolean {
        val r = remote.editedAt(g)
        val l = local.editedAt(g)
        return if (r != l) r > l else content(remote, g) > content(local, g)
    }

    /** Returns null when the remote copy brings nothing new. */
    fun merge(local: Place?, remote: Place, now: Long, localName: String): Outcome? {
        if (local == null) {
            return Outcome(remote.copy(id = 0), conflict = false, newEan = remote.ean.takeIf { !remote.deleted })
        }
        // Spell out every group's time so a copy from an old phone does not leave zeros behind.
        var m = Group.entries.fold(local) { p, g -> p.stamp(g, local.editedAt(g)) }
        var conflict = false
        var newEan: String? = null
        for (g in Group.entries) if (remoteWins(remote, local, g)) m = take(m, remote, g)
        if (m.ean != local.ean && m.ean != null) newEan = m.ean
        if (m.ean != local.ean && !m.deleted && !local.deleted && local.ean != null && m.ean != null &&
            !m.problemNote.contains(local.ean)
        ) {
            conflict = true
            m = m.copy(
                problem = true,
                problemResolved = false,
                problemNote = joinNotes(
                    m.problemNote,
                    "EAN различается: ${local.ean} (${local.scannedBy.ifBlank { localName }}) и ${m.ean} — оставлен ${m.ean}",
                ),
            ).stamp(Group.PROBLEM, maxOf(now, m.editedAt(Group.PROBLEM) + 1, remote.updatedAt + 1))
        }
        if (remote.scannedAt < m.scannedAt) m = m.copy(scannedAt = remote.scannedAt, scannedBy = remote.scannedBy)
        if (remote.verified && !m.verified) m = m.copy(verified = true)
        return if (m == local) null else Outcome(m, conflict, newEan)
    }

    fun joinNotes(a: String, b: String): String = when {
        b.isBlank() || a.contains(b) -> a
        a.isBlank() -> b
        else -> "$a; $b"
    }
}
