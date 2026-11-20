package app.aura.ring.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Eight log-spaced spectrum bands from the global audio mix. Visualizer is the
 * only public API that can see other apps' output, hence the RECORD_AUDIO
 * permission.
 */
class AudioEngine(private val context: Context) {
    private var visualizer: Visualizer? = null
    private val peaks = FloatArray(8) { 0.08f }
    private val smoothed = FloatArray(8)

    @Volatile
    var isActive: Boolean = false
        private set

    fun start(): Boolean {
        if (isActive) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val v = Visualizer(0)
            val capture = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.captureSize = capture
            val status = v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (fft != null) process(fft)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            if (status != Visualizer.SUCCESS) {
                v.release()
                return false
            }
            v.enabled = true
            visualizer = v
            isActive = true
            true
        } catch (_: Throwable) {
            runCatching { visualizer?.release() }
            visualizer = null
            isActive = false
            false
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
        } catch (_: Throwable) {
        }
        runCatching { visualizer?.release() }
        visualizer = null
        isActive = false
        synchronized(smoothed) {
            smoothed.fill(0f)
            AppState.bands.value = FloatArray(8)
        }
    }

    private fun process(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < 12) return
        synchronized(smoothed) {
            val minBin = 1.0
            val maxBin = bins.toDouble() - 1.0
            for (i in 0 until 8) {
                val lo = (minBin * (maxBin / minBin).pow(i / 8.0))
                    .toInt().coerceIn(1, bins - 1)
                val hi = (minBin * (maxBin / minBin).pow((i + 1) / 8.0))
                    .toInt().coerceIn(lo + 1, bins)
                var magnitude = 0f
                for (bin in lo until hi) {
                    val real = fft[bin * 2].toInt()
                    val imaginary = fft[bin * 2 + 1].toInt()
                    val mag = sqrt((real * real + imaginary * imaginary).toFloat()) / 128f
                    if (mag > magnitude) magnitude = mag
                }
                peaks[i] = max(magnitude, peaks[i] * 0.995f).coerceAtLeast(0.02f)
                val normalized = (magnitude / peaks[i]).coerceIn(0f, 1f)
                smoothed[i] = smoothed[i] * 0.55f + normalized * 0.45f
            }
            AppState.bands.value = smoothed.copyOf()
        }
    }
}
