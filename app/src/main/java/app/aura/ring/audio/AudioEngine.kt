package app.aura.ring.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single, consistent snapshot of what the music is doing. Directions:
 * level/bass/mid/treble/bands are 0..1, [beat] is a decaying 0..1 envelope,
 * [bpm] is the median estimate over the last few beats.
 */
data class AudioState(
    val level: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val beat: Float = 0f,
    val beatCount: Long = 0L,
    val bpm: Int = 0,
    val bands: FloatArray = FloatArray(8),
    val hasSignal: Boolean = false,
    val silent: Boolean = true,
    val running: Boolean = false,
    val error: String? = null,
    val source: String = "System audio",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioState) return false
        return level == other.level && bass == other.bass && mid == other.mid &&
            treble == other.treble && beat == other.beat && beatCount == other.beatCount &&
            bpm == other.bpm && hasSignal == other.hasSignal && silent == other.silent &&
            running == other.running && error == other.error && source == other.source &&
            bands.contentEquals(other.bands)
    }

    override fun hashCode(): Int {
        var result = level.hashCode()
        result = 31 * result + bass.hashCode()
        result = 31 * result + mid.hashCode()
        result = 31 * result + treble.hashCode()
        result = 31 * result + beat.hashCode()
        result = 31 * result + beatCount.hashCode()
        result = 31 * result + bpm
        result = 31 * result + bands.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Music analysis for the ring.
 *
 * Two sources: the system output mix (Visualizer, session 0) and the
 * microphone (AudioRecord + a small FFT). Both feed the same smoothing,
 * peak-normalisation, auto-gain and beat tracker, so every lighting mode sees
 * identical numbers.
 *
 * The capture is self-healing: a failed start is retried with backoff, and a
 * stream that goes quiet for too long is rebuilt instead of leaving the ring
 * frozen on the last frame. Nothing runs while the ring is idle.
 */
class AudioEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    @Volatile private var wantRunning = false
    @Volatile private var captureActive = false
    @Volatile private var source = AudioSource.SYSTEM
    @Volatile private var error: String? = null
    @Volatile private var lastDataAt = 0L

    private var sensitivity = 1f
    private var autoSensitivity = true

    private var visualizer: Visualizer? = null
    private var record: AudioRecord? = null
    private var micJob: Job? = null
    private var retryJob: Job? = null

    // ---- DSP state -------------------------------------------------------
    private val bands = FloatArray(BANDS)
    private val peaks = FloatArray(BANDS) { 0.03f }
    private val bandSmoothed = FloatArray(BANDS)
    private val rawBands = FloatArray(BANDS)
    private var levelSmoothed = 0f
    private var levelPeak = 0.05f
    private var autoGain = 1f
    private var beatEnvelope = 0f
    private var lastBeatMs = 0L
    private var beatCount = 0L
    private var silentSince = 0L
    private var lastFeedMs = 0L
    private var lastPublishMs = 0L

    private val beatHistory = FloatArray(48)
    private var beatHistoryIndex = 0
    private var beatHistoryCount = 0

    private val beatIntervals = FloatArray(8)
    private var beatIntervalIndex = 0
    private var beatIntervalCount = 0
    private var bpm = 0

    private var captureSize = 1024
    private var sampleRateHz = 44100
    private var bandOfBin: IntArray = IntArray(0)
    private var mappedRate = -1

    private val fft = RealFft(FFT_SIZE)
    private val fftOut = FloatArray(FFT_SIZE / 2)
    private val window = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
    }
    private val micSamples = FloatArray(FFT_SIZE)

    private var stalls = 0
    private var retryAttempts = 0

    fun configure(source: AudioSource, sensitivity: Float, auto: Boolean) {
        this.sensitivity = sensitivity.coerceIn(0.2f, 3f)
        this.autoSensitivity = auto
        if (source != this.source) {
            this.source = source
            if (wantRunning) {
                synchronized(lock) { resetDsp() }
                restartCapture()
            }
        }
    }

    fun setRunning(on: Boolean) {
        if (on == wantRunning) return
        wantRunning = on
        if (on) startCapture() else stopCapture()
    }

    fun stop() {
        wantRunning = false
        stopCapture()
    }

    /** Final teardown. The engine cannot be restarted after this. */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun startCapture() {
        if (captureActive) return
        stalls = 0
        val ok = when (source) {
            AudioSource.MIC -> startMic()
            AudioSource.SYSTEM -> startVisualizer()
        }
        if (ok) {
            captureActive = true
            retryAttempts = 0
            lastDataAt = SystemClock.elapsedRealtime()
            error = null
        } else {
            captureActive = false
            if (error == null) error = "Audio capture unavailable"
            scheduleRetry()
        }
        publish(force = true)
    }

    private fun stopCapture() {
        retryJob?.cancel()
        retryJob = null
        retryAttempts = 0
        stopVisualizer()
        stopMic()
        captureActive = false
        error = null
        synchronized(lock) { resetDsp() }
        publish(force = true)
    }

    private fun restartCapture() {
        stopVisualizer()
        stopMic()
        captureActive = false
        startCapture()
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (wantRunning && !captureActive) {
                val delayMs = (1_500L shl retryAttempts.coerceAtMost(3)).coerceAtMost(15_000L)
                retryAttempts = (retryAttempts + 1).coerceAtMost(4)
                delay(delayMs)
                if (!wantRunning || captureActive) return@launch

                val ok = when (source) {
                    AudioSource.MIC -> startMic()
                    AudioSource.SYSTEM -> startVisualizer()
                }
                if (ok) {
                    captureActive = true
                    retryAttempts = 0
                    stalls = 0
                    lastDataAt = SystemClock.elapsedRealtime()
                    error = null
                    publish(force = true)
                    return@launch
                }
            }
        }
    }

    /** Watchdog: rebuild a stream that stopped delivering data. */
    private val watchdog = scope.launch {
        while (isActive) {
            delay(2000)
            if (!wantRunning) continue
            val now = SystemClock.elapsedRealtime()
            if (!captureActive) {
                if (retryJob?.isActive != true) scheduleRetry()
                continue
            }
            if (now - lastDataAt > STALL_MS) {
                stalls++
                if (stalls <= 3) {
                    error = "Audio stream stalled, reconnecting"
                    restartCapture()
                } else if (error == null) {
                    error = "No audio data. Try the microphone source."
                }
            }
        }
    }

    // ---- system output ---------------------------------------------------

    private val visualizerListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
            visualizer: Visualizer?,
            waveform: ByteArray?,
            samplingRate: Int,
        ) {
            if (waveform == null || waveform.isEmpty()) return
            var sum = 0.0
            for (b in waveform) {
                val v = ((b.toInt() and 0xFF) - 128) / 128f
                sum += v * v
            }
            val rms = sqrt(sum / waveform.size).toFloat()
            feed(rms * 2.2f, null, SystemClock.elapsedRealtime())
        }

        override fun onFftDataCapture(
            visualizer: Visualizer?,
            fftData: ByteArray?,
            samplingRate: Int,
        ) {
            if (fftData == null || fftData.isEmpty()) return
            sampleRateHz = sampleRateToHz(samplingRate)
            analyseFft(fftData)
            feed(null, rawBands, SystemClock.elapsedRealtime())
        }
    }

    private fun startVisualizer(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                error = "Microphone permission needed for audio capture"
                return false
            }
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            val size = if (range != null && range.size >= 2) {
                DEFAULT_CAPTURE.coerceIn(range[0], range[1])
            } else {
                DEFAULT_CAPTURE
            }
            v.captureSize = size
            captureSize = size
            mappedRate = -1
            val status = v.setDataCaptureListener(
                visualizerListener,
                Visualizer.getMaxCaptureRate(),
                true,
                true,
            )
            if (status != Visualizer.SUCCESS) {
                v.release()
                error = "Visualizer refused the capture listener"
                Log.w(TAG, "setDataCaptureListener status=$status")
                return false
            }
            v.enabled = true
            visualizer = v
            error = null
            Log.d(TAG, "Visualizer started, size=$size")
            true
        } catch (t: Throwable) {
            runCatching { visualizer?.release() }
            visualizer = null
            error = "Audio capture failed: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, "Visualizer failed", t)
            false
        }
    }

    private fun stopVisualizer() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false }
        runCatching { v.release() }
    }

    /**
     * Converts the Visualizer FFT (pairs of signed real/imaginary bytes) into
     * eight log-spaced bands. The band table is cached per sample rate.
     */
    private fun analyseFft(data: ByteArray) {
        val bins = captureSize / 2
        if (bins < 16 || data.size < bins * 2) return
        val nyquist = sampleRateHz / 2f
        if (mappedRate != sampleRateHz) {
            mappedRate = sampleRateHz
            bandOfBin = IntArray(bins + 1) { bin ->
                if (bin == 0) -1 else bandFor(bin * nyquist / bins)
            }
        }
        val sums = FloatArray(BANDS)
        val counts = IntArray(BANDS)
        for (bin in 1 until bins) {
            val re = data[bin * 2].toInt()
            val im = data[bin * 2 + 1].toInt()
            val mag = sqrt((re * re + im * im).toFloat()) / 128f
            val band = bandOfBin.getOrElse(bin) { -1 }
            if (band >= 0) {
                sums[band] += mag
                counts[band]++
            }
        }
        for (i in 0 until BANDS) {
            rawBands[i] = if (counts[i] > 0) sums[i] / counts[i] else 0f
        }
    }

    private fun bandFor(hz: Float): Int {
        for (i in 0 until BANDS) {
            if (hz < BAND_EDGES[i + 1]) return i
        }
        return BANDS - 1
    }

    // ---- microphone ------------------------------------------------------

    private fun startMic(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error = "Microphone permission needed"
            return false
        }
        val sources = ArrayList<Int>(3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sources.add(MediaRecorder.AudioSource.MIC)

        for (src in sources) {
            val rec = buildRecorder(src) ?: continue
            record = rec
            sampleRateHz = MIC_RATE
            mappedRate = MIC_RATE
            micJob = scope.launch { micLoop(rec) }
            error = null
            return true
        }
        error = "Microphone is busy"
        return false
    }

    private fun buildRecorder(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        var rec: AudioRecord? = null
        return try {
            rec = AudioRecord(
                source,
                MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FFT_SIZE * 4),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                return null
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { rec.release() }
                return null
            }
            rec
        } catch (_: Throwable) {
            runCatching { rec?.release() }
            null
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private suspend fun micLoop(rec: AudioRecord) {
        val buffer = ShortArray(FFT_SIZE)
        try {
            while (scope.isActive && wantRunning) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) {
                        error = "Microphone read error $read"
                        break
                    }
                    continue
                }
                var sum = 0.0
                for (i in 0 until read) {
                    val v = buffer[i] / 32768f
                    micSamples[i] = v
                    sum += v * v
                }
                for (i in read until FFT_SIZE) micSamples[i] = 0f
                val rms = sqrt(sum / read).toFloat()

                fft.magnitudes(micSamples, window, fftOut)
                fillBandsFromMagnitudes(fftOut, MIC_RATE)
                feed(rms * 2.4f, rawBands, SystemClock.elapsedRealtime())
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            error = "Microphone failed: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            if (record === rec) {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                record = null
            }
            if (wantRunning && captureActive) {
                captureActive = false
                scheduleRetry()
            }
        }
    }

    private fun fillBandsFromMagnitudes(mags: FloatArray, rate: Int) {
        val nyquist = rate / 2f
        val bins = mags.size
        val sums = FloatArray(BANDS)
        val counts = IntArray(BANDS)
        for (bin in 1 until bins) {
            val hz = bin * nyquist / bins
            val band = bandFor(hz)
            sums[band] += mags[bin]
            counts[band]++
        }
        for (i in 0 until BANDS) {
            rawBands[i] = if (counts[i] > 0) sums[i] / counts[i] else 0f
        }
    }

    // ---- shared DSP ------------------------------------------------------

    /**
     * @param levelIn RMS from a waveform, or null when only an FFT arrived.
     * @param bandIn raw band magnitudes, or null when only a waveform arrived.
     */
    private fun feed(levelIn: Float?, bandIn: FloatArray?, now: Long) {
        synchronized(lock) {
            lastDataAt = now
            val dt = if (lastFeedMs == 0L) 33f else (now - lastFeedMs).coerceIn(4L, 250L).toFloat()
            lastFeedMs = now

            if (bandIn != null) {
                for (i in 0 until BANDS) {
                    val raw = bandIn[i].coerceIn(0f, 4f)
                    peaks[i] = max(raw, peaks[i] * exp(-dt / 1400f)).coerceAtLeast(0.006f)
                    val norm = (raw / peaks[i]).coerceIn(0f, 1f)
                    val tau = if (norm > bandSmoothed[i]) 18f else 170f
                    val k = 1f - exp(-dt / tau)
                    bandSmoothed[i] += (norm - bandSmoothed[i]) * k
                }
            }

            if (levelIn != null) {
                val raw = levelIn.coerceIn(0f, 4f)
                levelPeak = max(raw, levelPeak * exp(-dt / 2200f)).coerceAtLeast(0.004f)
                val norm = (raw / levelPeak).coerceIn(0f, 1f)
                val tau = if (norm > levelSmoothed) 27f else 260f
                val k = 1f - exp(-dt / tau)
                levelSmoothed += (norm - levelSmoothed) * k
            } else {
                // No waveform (some devices only deliver FFT): derive the level
                // from the band energies so brightness still follows the music.
                var sum = 0f
                for (i in 0 until BANDS) sum += bandSmoothed[i]
                levelSmoothed += ((sum / BANDS) - levelSmoothed) * 0.18f
            }

            if (autoSensitivity) {
                val target = 0.55f
                val needed = (target / max(levelPeak, 0.05f)).coerceIn(0.6f, 5f)
                autoGain += (needed - autoGain) * 0.05f
            } else {
                autoGain = sensitivity
            }

            for (i in 0 until BANDS) {
                bands[i] = (bandSmoothed[i] * autoGain).coerceIn(0f, 1f)
            }
            val level = (levelSmoothed * autoGain).coerceIn(0f, 1f)
            val bass = ((bands[0] + bands[1]) * 0.5f).coerceIn(0f, 1f)
            val mid = ((bands[2] + bands[3] + bands[4]) / 3f).coerceIn(0f, 1f)
            val treble = ((bands[5] + bands[6] + bands[7]) / 3f).coerceIn(0f, 1f)

            detectBeat(bass * 0.65f + level * 0.35f, now, dt)

            val quiet = level < 0.02f && bass < 0.03f
            if (quiet) {
                if (silentSince == 0L) silentSince = now
            } else {
                silentSince = 0L
            }
            val silent = silentSince != 0L && now - silentSince > SILENCE_MS

            beatEnvelope = if (lastBeatMs == 0L) 0f
            else exp(-(now - lastBeatMs) / 150f).toFloat().coerceIn(0f, 1f)

            publishLocked(level, bass, mid, treble, silent, now)
        }
    }

    private fun detectBeat(energy: Float, now: Long, dt: Float) {
        beatHistory[beatHistoryIndex] = energy
        beatHistoryIndex = (beatHistoryIndex + 1) % beatHistory.size
        if (beatHistoryCount < beatHistory.size) beatHistoryCount++

        var sum = 0f
        for (i in 0 until beatHistoryCount) sum += beatHistory[i]
        val baseline = sum / beatHistoryCount.coerceAtLeast(1)

        val refractory = if (bpm > 0) {
            (60_000f / bpm * 0.42f).toLong().coerceIn(170L, 420L)
        } else {
            220L
        }
        val threshold = max(baseline * (1.28f / sensitivity.coerceAtLeast(0.4f)), 0.06f)
        if (energy <= threshold) return
        if (lastBeatMs != 0L && now - lastBeatMs < refractory) return

        if (lastBeatMs != 0L) {
            val gap = (now - lastBeatMs).toFloat()
            if (gap in 250f..1500f) {
                beatIntervals[beatIntervalIndex] = gap
                beatIntervalIndex = (beatIntervalIndex + 1) % beatIntervals.size
                if (beatIntervalCount < beatIntervals.size) beatIntervalCount++
                val sorted = FloatArray(beatIntervalCount)
                for (i in 0 until beatIntervalCount) {
                    sorted[i] = beatIntervals[(beatIntervalIndex - beatIntervalCount + i + beatIntervals.size * 2) % beatIntervals.size]
                }
                sorted.sort()
                val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
                bpm = (60_000f / median).toInt().coerceIn(40, 220)
            }
        }
        lastBeatMs = now
        beatCount++
        beatEnvelope = 1f
    }

    private fun publishLocked(
        level: Float,
        bass: Float,
        mid: Float,
        treble: Float,
        silent: Boolean,
        now: Long,
    ) {
        if (!silent && now - lastPublishMs < 33) return
        if (silent && now - lastPublishMs < 250) return
        lastPublishMs = now
        val hasSignal = !silent && level > 0.02f
        AppState.setAudio(
            AudioState(
                level = level,
                bass = bass,
                mid = mid,
                treble = treble,
                beat = beatEnvelope,
                beatCount = beatCount,
                bpm = bpm,
                bands = bands.copyOf(),
                hasSignal = hasSignal,
                silent = silent,
                running = captureActive,
                error = error,
                source = if (source == AudioSource.MIC) "Microphone" else "System audio",
            )
        )
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (force) lastPublishMs = 0
            publishLocked(
                level = if (captureActive) levelSmoothed else 0f,
                bass = 0f,
                mid = 0f,
                treble = 0f,
                silent = true,
                now = now,
            )
        }
    }

    private fun resetDsp() {
        bands.fill(0f)
        bandSmoothed.fill(0f)
        peaks.fill(0.03f)
        levelSmoothed = 0f
        levelPeak = 0.05f
        autoGain = 1f
        beatEnvelope = 0f
        lastBeatMs = 0L
        beatCount = 0L
        silentSince = 0L
        lastFeedMs = 0L
        beatHistoryCount = 0
        beatHistoryIndex = 0
        beatHistory.fill(0f)
        beatIntervalCount = 0
        beatIntervalIndex = 0
        bpm = 0
    }

    private fun sampleRateToHz(milliHz: Int): Int {
        val hz = if (milliHz > 0) milliHz / 1000 else 0
        return if (hz in 8000..192000) hz else 44100
    }

    private val hann = window

    private class RealFft(private val n: Int) {
        private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
        private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
        private val re = FloatArray(n)
        private val im = FloatArray(n)
        private val reversed = IntArray(n)
        private var bits = 0

        init {
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var x = i
                var y = 0
                for (b in 0 until bits) {
                    y = (y shl 1) or (x and 1)
                    x = x shr 1
                }
                reversed[i] = y
            }
        }

        fun magnitudes(samples: FloatArray, win: FloatArray, out: FloatArray) {
            for (i in 0 until n) {
                val v = if (i < samples.size) samples[i] * win[i] else 0f
                val j = reversed[i]
                re[j] = v
                im[j] = 0f
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var i = 0
                while (i < n) {
                    for (j in 0 until half) {
                        val k = j * step
                        val cosV = cosTable[k]
                        val sinV = sinTable[k]
                        val xr = re[i + j + half]
                        val xi = im[i + j + half]
                        val tr = xr * cosV + xi * sinV
                        val ti = -xr * sinV + xi * cosV
                        re[i + j + half] = re[i + j] - tr
                        im[i + j + half] = im[i + j] - ti
                        re[i + j] += tr
                        im[i + j] += ti
                    }
                    i += size
                }
                size *= 2
            }
            val scale = 2f / n
            for (i in 0 until n / 2) {
                val r = re[i] * scale
                val m = im[i] * scale
                out[i] = sqrt(r * r + m * m)
            }
        }
    }

    companion object {
        private const val TAG = "AuraAudio"
        private const val BANDS = 8

        /** Log-spaced edges from 45 Hz to 16 kHz. */
        private val BAND_EDGES = FloatArray(BANDS + 1) { i ->
            (45.0 * (16000.0 / 45.0).pow(i / BANDS.toDouble())).toFloat()
        }

        private const val DEFAULT_CAPTURE = 1024
        private const val FFT_SIZE = 512
        private const val MIC_RATE = 22050
        private const val SILENCE_MS = 1500L
        private const val STALL_MS = 7000L
    }
}
