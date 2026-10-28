package ru.dnsflow.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dnsflow.app.App
import ru.dnsflow.app.data.AppDb
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Session
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV export (opens in Excel) and database backup/restore. */
object Export {
    private val dao get() = App.instance.dao
    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    private val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    suspend fun shareSession(context: Context, sessionId: Long) {
        val session = dao.session(sessionId) ?: return
        val file = writeCsv(context, "priemka_${sessionId}_$stamp.csv", listOf(session), dao.placesOnce(sessionId))
        share(context, file, "text/csv")
    }

    suspend fun shareAll(context: Context) {
        val places = dao.allPlaces()
        val sessions = places.map { it.sessionId }.distinct().mapNotNull { dao.session(it) }
        val file = writeCsv(context, "priemka_all_$stamp.csv", sessions, places)
        share(context, file, "text/csv")
    }

    private suspend fun writeCsv(context: Context, name: String, sessions: List<Session>, places: List<Place>): File =
        withContext(Dispatchers.IO) {
            val products = dao.productsOnce().associateBy { it.ean }
            val titles = sessions.associate { it.id to it.title }
            val sb = StringBuilder("﻿") // BOM so Excel reads UTF-8
            sb.appendLine(
                listOf(
                    "Приёмка", "№", "Грузовое место", "Код с этикетки", "Тип кода", "Этикетка от", "Отсканировано",
                    "EAN", "Код DNS", "Товар", "Ссылка", "Проблема", "Комментарий", "Решено", "Проверено в 1С", "Фото",
                    "Частей", "EAN отдельных частей",
                ).joinToString(";")
            )
            for (p in places) {
                val info = Codes.parsePlace(p.raw)
                val prod = p.ean?.let { products[it] }
                sb.appendLine(
                    listOf(
                        titles[p.sessionId] ?: "", p.position.toString(), info.number, p.raw, Codes.label(p.format),
                        info.printedAt ?: "", timeFmt.format(Date(p.scannedAt)),
                        p.ean ?: "", prod?.code ?: "", prod?.name ?: "", prod?.url ?: "",
                        if (p.problem) "да" else "", p.problemNote, if (p.problemResolved) "да" else "",
                        if (p.verified) "да" else "",
                        p.photos.lines().count { it.isNotBlank() }.takeIf { it > 0 }?.toString() ?: "",
                        p.pieces.toString(), p.extraEans.lines().filter { it.isNotBlank() }.joinToString(" "),
                    ).joinToString(";") { csv(it) }
                )
            }
            exportFile(context, name).apply { writeText(sb.toString()) }
        }

    /** Copies the database file so it can be saved to Drive/Telegram/etc. */
    suspend fun shareBackup(context: Context) {
        val file = withContext(Dispatchers.IO) {
            App.instance.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            val out = exportFile(context, "priemka_backup_$stamp.db")
            context.getDatabasePath(AppDb.NAME).copyTo(out, overwrite = true)
            out
        }
        share(context, file, "application/octet-stream")
    }

    /** Replaces the database with a backup and restarts the app. */
    suspend fun restoreBackup(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "restore.db")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: return@withContext false
        val header = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (!String(header).startsWith("SQLite format 3")) return@withContext false
        App.instance.db.close()
        val dbFile = context.getDatabasePath(AppDb.NAME)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        tmp.copyTo(dbFile, overwrite = true)
        tmp.delete()
        true
    }

    fun exportFile(context: Context, name: String): File =
        File(context.cacheDir, "exports").apply { mkdirs() }.resolve(name)

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Сохранить / отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun csv(v: String): String =
        if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
