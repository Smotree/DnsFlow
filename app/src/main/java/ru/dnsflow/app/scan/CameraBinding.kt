package ru.dnsflow.app.scan

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Binds a screen's camera use cases to the back camera and releases only those.
 *
 * During a screen change both screens are on screen for a moment: the new one binds first and
 * the old one is disposed after. Releasing with `unbindAll()` then stopped the new screen's
 * camera too and its preview froze. CameraX keeps only the most recently started screen active,
 * so each screen just binds and unbinds its own use cases.
 *
 * @return a release function to call from `onDispose`.
 */
fun bindBackCamera(
    context: Context,
    owner: LifecycleOwner,
    useCases: List<UseCase>,
    onCamera: (Camera) -> Unit = {},
): () -> Unit {
    var released = false
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        if (released) return@addListener // the screen closed before the camera was ready
        runCatching {
            onCamera(future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()))
        }.onFailure { Log.w("Camera", "bind failed", it) }
    }, ContextCompat.getMainExecutor(context))
    return {
        released = true
        if (future.isDone) runCatching { future.get().unbind(*useCases.toTypedArray()) }
    }
}
