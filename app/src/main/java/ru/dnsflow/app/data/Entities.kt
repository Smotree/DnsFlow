package ru.dnsflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One truck acceptance. */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** Shared id of the acceptance; files from other phones with the same uid merge into it. */
    @ColumnInfo(defaultValue = "''") val uid: String = "",
    /** Equal split of the 1C check between phones: number of parts and this phone's part (0 = none). */
    @ColumnInfo(defaultValue = "0") val splitParts: Int = 0,
    @ColumnInfo(defaultValue = "0") val myPart: Int = 0,
    /** Shared mode to restore after an app restart: 0 none, 1 this phone hosts, 2 joined [syncHost]. */
    @ColumnInfo(defaultValue = "0") val syncRole: Int = 0,
    @ColumnInfo(defaultValue = "''") val syncHost: String = "",
)

/**
 * A scanned cargo place. [raw] is kept byte-for-byte as read from the label so the
 * code can be regenerated later and read by the 1C scanner as the same value.
 * [format] is a zxing BarcodeFormat name (QR_CODE, CODE_128, ...).
 */
@Entity(tableName = "places", indices = [Index("sessionId"), Index("ean")])
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val position: Int,
    val raw: String,
    val format: String,
    val manual: Boolean = false,
    val ean: String? = null,
    val eanFormat: String? = null,
    val scannedAt: Long,
    /** Phone that scanned the place, set for places merged in from another phone. */
    @ColumnInfo(defaultValue = "''") val scannedBy: String = "",
    val problem: Boolean = false,
    val problemNote: String = "",
    val problemResolved: Boolean = false,
    val verified: Boolean = false,
    /** Last local or synced edit, for last-write-wins between phones. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** Soft delete so a removal also reaches the other phones. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Part of the split 1C check this place belongs to (0 = not split). */
    @ColumnInfo(defaultValue = "0") val part: Int = 0,
    /** Photo file names (one per line) in the app's photos folder; kept on this phone only. */
    @ColumnInfo(defaultValue = "''") val photos: String = "",
    /**
     * Physical pieces under this one cargo-place label: the box itself plus items the warehouse
     * sent separately with the same label (e.g. 3 kettles next to the box) — 1 = just the box.
     */
    @ColumnInfo(defaultValue = "1") val pieces: Int = 1,
    /** EANs scanned on the separate pieces, one per line (repeats = several of the same product). */
    @ColumnInfo(defaultValue = "''") val extraEans: String = "",
    /** Unused: reading the label text was tried and removed (not reliable on all labels); kept so the schema stays. */
    @ColumnInfo(defaultValue = "''") val sender: String = "",
    @ColumnInfo(defaultValue = "''") val recipient: String = "",
)

enum class LookupStatus { PENDING, FOUND, NOT_FOUND, ERROR }

/** Product info looked up on the DNS site by EAN. */
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val ean: String,
    val status: LookupStatus,
    val code: String? = null,
    val name: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SessionSummary(
    @Embedded val session: Session,
    val placeCount: Int,
    val eanCount: Int,
    val problemCount: Int,
)

/** A finished session whose codes were not all shown to 1C yet. */
data class PendingCheck(
    @Embedded val session: Session,
    val remaining: Int,
)

data class ProblemRow(
    @Embedded val place: Place,
    val sessionTitle: String,
)
