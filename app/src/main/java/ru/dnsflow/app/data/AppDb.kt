package ru.dnsflow.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Sessions
    @Insert suspend fun insertSession(s: Session): Long
    @Update suspend fun updateSession(s: Session)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: Long): Session?
    @Query("SELECT * FROM sessions WHERE id = :id") fun sessionFlow(id: Long): Flow<Session?>
    @Query("SELECT * FROM sessions WHERE finishedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun openSession(): Flow<Session?>
    @Query("SELECT * FROM sessions WHERE uid = :uid LIMIT 1") suspend fun sessionByUid(uid: String): Session?
    @Query("UPDATE sessions SET syncRole = 0 WHERE id != :keep") suspend fun clearSyncRoleExcept(keep: Long)
    @Query("UPDATE places SET sessionId = :to WHERE sessionId = :from") suspend fun movePlaces(from: Long, to: Long)

    @Query(
        """SELECT s.*,
           (SELECT COUNT(*) FROM places p WHERE p.sessionId = s.id AND p.deleted = 0) AS placeCount,
           (SELECT COUNT(*) FROM places p WHERE p.sessionId = s.id AND p.deleted = 0 AND p.ean IS NOT NULL) AS eanCount,
           (SELECT COUNT(*) FROM places p WHERE p.sessionId = s.id AND p.deleted = 0 AND p.problem = 1) AS problemCount
           FROM sessions s ORDER BY s.createdAt DESC"""
    )
    fun sessionSummaries(): Flow<List<SessionSummary>>

    @Query(
        """SELECT s.*, (SELECT COUNT(*) FROM places p WHERE p.sessionId = s.id AND p.deleted = 0 AND p.verified = 0) AS remaining
           FROM sessions s
           WHERE s.finishedAt IS NOT NULL
             AND EXISTS (SELECT 1 FROM places p WHERE p.sessionId = s.id AND p.deleted = 0 AND p.verified = 0)
           ORDER BY s.finishedAt DESC LIMIT 1"""
    )
    fun pendingCheck(): Flow<PendingCheck?>

    @Query("DELETE FROM places WHERE sessionId = :id") suspend fun deletePlacesOf(id: Long)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSessionRow(id: Long)

    // Places
    @Insert suspend fun insertPlace(p: Place): Long
    @Update suspend fun updatePlace(p: Place)
    @Query("SELECT * FROM places WHERE id = :id") suspend fun place(id: Long): Place?
    @Query("SELECT * FROM places WHERE id = :id") fun placeFlow(id: Long): Flow<Place?>
    @Query("SELECT * FROM places WHERE sessionId = :sid AND deleted = 0 ORDER BY position") fun places(sid: Long): Flow<List<Place>>
    @Query("SELECT * FROM places WHERE sessionId = :sid AND deleted = 0 ORDER BY position") suspend fun placesOnce(sid: Long): List<Place>
    /** Including deleted ones: used for sync (deletions must reach other phones too). */
    @Query("SELECT * FROM places WHERE sessionId = :sid") suspend fun placesWithDeleted(sid: Long): List<Place>
    @Query("SELECT * FROM places WHERE sessionId = :sid AND raw = :raw LIMIT 1")
    suspend fun findPlace(sid: Long, raw: String): Place?
    @Query("SELECT COALESCE(MAX(position), 0) FROM places WHERE sessionId = :sid AND deleted = 0") suspend fun maxPosition(sid: Long): Int
    @Query("SELECT * FROM places WHERE deleted = 0 ORDER BY sessionId, position") suspend fun allPlaces(): List<Place>

    @Query(
        """SELECT p.*, s.title AS sessionTitle FROM places p JOIN sessions s ON s.id = p.sessionId
           WHERE p.problem = 1 AND p.deleted = 0 ORDER BY p.problemResolved, p.scannedAt DESC"""
    )
    fun problems(): Flow<List<ProblemRow>>
    @Query("SELECT * FROM places WHERE problem = 1 AND problemResolved = 0 AND deleted = 0 ORDER BY sessionId, position")
    fun openProblemPlaces(): Flow<List<Place>>
    @Query("SELECT COUNT(*) FROM places WHERE problem = 1 AND problemResolved = 0 AND deleted = 0")
    fun openProblemCount(): Flow<Int>

    // Products
    @Upsert suspend fun upsertProduct(p: Product)
    @Query("SELECT * FROM products WHERE ean = :ean") suspend fun product(ean: String): Product?
    @Query("SELECT * FROM products") fun products(): Flow<List<Product>>
    @Query("SELECT * FROM products") suspend fun productsOnce(): List<Product>
    @Query("SELECT * FROM products WHERE status IN ('PENDING', 'ERROR') AND attempts < 5")
    suspend fun pendingProducts(): List<Product>
    @Query("UPDATE products SET attempts = 0 WHERE status IN ('ERROR', 'NOT_FOUND')")
    suspend fun resetFailedProducts()
    @Query("UPDATE products SET status = 'PENDING', attempts = 0 WHERE status = 'FOUND'")
    suspend fun markFoundPending()
    @Query("UPDATE products SET status = 'PENDING' WHERE status IN ('ERROR', 'NOT_FOUND')")
    suspend fun markFailedPending()
}

@Database(entities = [Session::class, Place::class, Product::class], version = 7, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val NAME = "dnsflow.db"

        fun open(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE places ADD COLUMN scannedBy TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN splitParts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN myPart INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE places ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE places ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE places ADD COLUMN part INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE places SET updatedAt = scannedAt")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN syncRole INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN syncHost TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN photos TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN sender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE places ADD COLUMN recipient TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN pieces INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE places ADD COLUMN extraEans TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
