package ru.dnsflow.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import ru.dnsflow.app.data.LookupStatus
import ru.dnsflow.app.data.Product
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Orange = Color(0xFFFC8507)
val ProblemRed = Color(0xFFD32F2F)
val OkGreen = Color(0xFF2E7D32)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Orange,
            onPrimary = Color.White,
            secondary = Color(0xFF333333),
            error = ProblemRed,
        ),
        content = content,
    )
}

private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
fun formatTime(ms: Long): String = dateFmt.format(Date(ms))

private val scanFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("ru"))
fun formatScanTime(ms: Long): String = scanFmt.format(Date(ms))

/** Renders [text] as the barcode [format], the way it was read from the label. */
fun renderCode(text: String, format: String, width: Int, height: Int): Bitmap? {
    val fmt = runCatching { BarcodeFormat.valueOf(format) }.getOrDefault(BarcodeFormat.CODE_128)
    val is2d = fmt in setOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417)
    val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to if (is2d) 2 else 10)
    if (fmt == BarcodeFormat.QR_CODE) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    val matrix = runCatching { MultiFormatWriter().encode(text, fmt, width, height, hints) }
        .recoverCatching { MultiFormatWriter().encode(text, BarcodeFormat.CODE_128, width, height, hints) }
        .getOrNull() ?: return null
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        pixels[y * w + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

@Composable
fun CodeImage(text: String, format: String, modifier: Modifier = Modifier) {
    val is2d = format in setOf("QR_CODE", "DATA_MATRIX", "AZTEC")
    val bmp = remember(text, format) { if (is2d) renderCode(text, format, 900, 900) else renderCode(text, format, 1200, 400) }
    Box(modifier.background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = text,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("Не удалось построить код", color = ProblemRed)
        }
    }
}

@Composable
fun ProductLine(ean: String?, product: Product?, modifier: Modifier = Modifier, large: Boolean = false) {
    if (ean == null) {
        Text("EAN не привязан", color = Color.Gray, fontSize = 13.sp, modifier = modifier)
        return
    }
    Column(modifier) {
        // DNS product code first: it is what gets checked in 1C.
        if (product?.code != null) {
            Text("Код товара ${product.code}", fontSize = if (large) 26.sp else 17.sp, fontWeight = FontWeight.Bold)
        }
        when (product?.status) {
            LookupStatus.FOUND -> product.name?.let { Text(it, fontSize = if (large) 17.sp else 14.sp, maxLines = 2) }
            LookupStatus.NOT_FOUND -> Text("Не найден на сайте", fontSize = 13.sp, color = ProblemRed)
            LookupStatus.ERROR -> Text("Ошибка поиска, повторим позже", fontSize = 13.sp, color = ProblemRed)
            LookupStatus.PENDING, null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Ищем код на сайте…", fontSize = 13.sp, color = Color.Gray)
            }
        }
        Text("EAN $ean", fontSize = 12.sp, color = Color.Gray)
    }
}

/** Small control drawn over the camera picture. */
@Composable
fun CameraChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0xFFFFD54F) else Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
