package ru.dnsflow.app.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ru.dnsflow.app.App
import ru.dnsflow.app.data.Codes
import java.util.concurrent.Executors

/**
 * Live camera preview that reports decoded codes as (raw text, zxing format name).
 * The same value is not reported again for [repeatMs] so a code held in front of the
 * camera is accepted once.
 */
@Composable
fun ScannerView(
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    torch: Boolean = false,
    repeatMs: Long = 3000,
    /** Chooses one of the codes seen in the same frame (raw, format); default = the first. */
    pick: (List<Pair<String, String>>) -> Pair<String, String>? = { it.firstOrNull() },
    onCode: (raw: String, format: String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(modifier.clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
        if (granted) {
            CameraPreview(paused, torch, repeatMs, pick, onCode)
            // Aiming frame
            Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .fillMaxHeight(0.6f)
                    .border(2.dp, if (paused) Color.Gray else Color(0xFFFC8507), RoundedCornerShape(12.dp))
            )
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(16.dp)) {
                Text("Разрешить камеру")
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    paused: Boolean,
    torch: Boolean,
    repeatMs: Long,
    pick: (List<Pair<String, String>>) -> Pair<String, String>?,
    onCode: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentPick by rememberUpdatedState(pick)
    val currentPaused by rememberUpdatedState(paused)
    // COMPATIBLE (TextureView) keeps the preview inside its bounds; the default
    // SurfaceView drew over the top bar and the list on MIUI.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        var lastValue = ""
        var lastAt = 0L
        val confirm = ReadConfirm()
        run {
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || currentPaused) {
                    proxy.close(); return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { codes ->
                        val seen = codes.mapNotNull { c -> c.rawValue?.takeIf { it.isNotEmpty() }?.let { it to Codes.formatName(c.format) } }
                        val (raw, format) = currentPick(seen) ?: return@addOnSuccessListener
                        val now = System.currentTimeMillis()
                        if (currentPaused) return@addOnSuccessListener
                        if (!confirm.feed(now, raw, format)) return@addOnSuccessListener
                        if (raw == lastValue && now - lastAt < repeatMs) {
                            lastAt = now // keep suppressing while the code stays in view
                            return@addOnSuccessListener
                        }
                        if (now - lastAt < 700) return@addOnSuccessListener
                        lastValue = raw
                        lastAt = now
                        currentOnCode(raw, format)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            val release = bindBackCamera(context, lifecycleOwner, listOf(preview, analysis)) { camera = it }
            onDispose {
                release()
                analysis.clearAnalyzer()
                scanner.close()
                executor.shutdown()
            }
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Sound + vibration after a scan. */
object Feedback {
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }

    fun ok(context: Context) = play(context, ToneGenerator.TONE_PROP_BEEP, 120, longArrayOf(0, 60))

    fun error(context: Context) = play(context, ToneGenerator.TONE_SUP_ERROR, 400, longArrayOf(0, 150, 100, 150))

    private fun play(context: Context, toneType: Int, toneMs: Int, pattern: LongArray) {
        val prefs = App.instance.prefs
        if (prefs.sound) tone?.startTone(toneType, toneMs)
        if (prefs.vibrate) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
