package ru.dnsflow.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dnsflow.app.App
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Repo
import java.io.File
import java.util.UUID

/** Optional photos of a problem place (crushed box, opened pack). Stored on this phone only. */
object Photos {
    fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }
    fun file(context: Context, name: String) = File(dir(context), name)
    fun names(place: Place) = place.photos.lines().filter { it.isNotBlank() }

    /** Shrinks the camera picture (several MB) to a readable 1600 px JPEG, upright. */
    fun saveScaled(context: Context, source: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        file(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        source.delete()
        return name
    }

    fun thumbnail(file: File, px: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    fun share(context: Context, name: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file(context, name))
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Отправить фото").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Opens the phone's camera for a place's photo; returns the function that starts it. */
@Composable
fun rememberPhotoTaker(onSaved: (placeId: Long) -> Unit = {}): (placeId: Long) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Long?>(null) }
    val capture = remember { File(File(context.cacheDir, "capture").apply { mkdirs() }, "photo.jpg") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val placeId = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (!ok) return@rememberLauncherForActivityResult
        App.instance.scope.launch {
            val name = withContext(Dispatchers.IO) { Photos.saveScaled(context, capture) } ?: return@launch
            Repo.addPhoto(placeId, name)
            onSaved(placeId)
        }
    }
    return { placeId ->
        pending = placeId
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", capture)
        runCatching { launcher.launch(uri) }
    }
}

/** Thumbnails of a place's photos with "add photo"; tap a photo to open it. */
@Composable
fun PhotoStrip(place: Place, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val take = rememberPhotoTaker()
    var open by remember { mutableStateOf<Int?>(null) }
    val names = Photos.names(place)
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, name -> Thumb(Photos.file(context, name), 64) { open = i } }
        OutlinedButton(onClick = { take(place.id) }) { Text(if (names.isEmpty()) "Сделать фото" else "+ фото") }
    }
    open?.let { start ->
        val scope = rememberCoroutineScope()
        PhotoViewer(
            names = names,
            start = start,
            onDelete = { name -> scope.launch { Repo.removePhoto(place.id, name) } },
            onDismiss = { open = null },
        )
    }
}

/** Full-screen photos: pinch or double-tap to zoom, drag to look around, swipe to the next one. */
@Composable
fun PhotoViewer(names: List<String>, start: Int, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (names.isEmpty()) { onDismiss(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, names.size - 1)) { names.size }
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(Photos.file(context, names[page]), onZoomed = { if (page == pager.currentPage) zoomed = it })
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("✕ Закрыть", color = Color.White) }
                Text(
                    if (names.size > 1) "${pager.currentPage + 1} / ${names.size}" else "",
                    color = Color.White, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { Photos.share(context, names[pager.currentPage]) }) { Text("Отправить", color = Color.White) }
                TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = Color(0xFFFF8A80)) }
            }
            if (!zoomed) {
                Text(
                    "Двумя пальцами или двойным касанием — увеличить",
                    color = Color(0xAAFFFFFF), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить фото?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(names[pager.currentPage])
                    onDismiss()
                }) { Text("Удалить", color = ProblemRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ZoomableImage(file: File, onZoomed: (Boolean) -> Unit) {
    var bmp by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) { bmp = withContext(Dispatchers.IO) { Photos.thumbnail(file, 2400) } }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset): Offset {
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else clamp(o)
        onZoomed(scale > 1f)
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) set(1f, Offset.Zero)
                    else set(2.5f, (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f)
                })
            }
            .pointerInput(Unit) {
                // Pinch always zooms; one-finger drags only pan when zoomed, otherwise the pager swipes.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f || scale > 1f) {
                            set(scale * zoom, offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it, null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                ),
            )
        } ?: Text("Загрузка…", color = Color.White)
    }
}

@Composable
private fun Thumb(file: File, px: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var bmp by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) { bmp = withContext(Dispatchers.IO) { Photos.thumbnail(file, px) } }
    val m = if (px <= 100) modifier.size(64.dp) else modifier
    Box(m.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0E0E0)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, contentScale = if (px <= 100) ContentScale.Crop else ContentScale.Fit, modifier = m) }
            ?: Text("…", fontSize = 12.sp)
    }
}
