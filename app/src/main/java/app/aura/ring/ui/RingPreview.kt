package app.aura.ring.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aura.ring.AppState
import app.aura.ring.BatteryInfo
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import app.aura.ring.core.Effects
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Live 8-pixel preview. While the service runs it mirrors the exact frames the
 * engine sends to the chip; when it is stopped, the same renderer runs in the
 * app so the preview still animates.
 */
@Composable
fun RingPreview(
    config: RingConfig,
    battery: BatteryInfo,
    modifier: Modifier = Modifier,
) {
    val audio by AppState.audio.collectAsState()
    val overlay by AppState.overlay.collectAsState()
    val backend by AppState.backend.collectAsState()
    val running by AppState.running.collectAsState()
    val engineFrame by AppState.frame.collectAsState()

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(40)
        }
    }
    val local = remember(now, config, battery, audio, overlay) {
        Effects.render(config, now, battery, audio, overlay)
    }
    val single = Effects.collapse(engineFrame)
    val colors = when {
        !running -> local
        backend == "Root" -> engineFrame
        // Shizuku drives the whole ring with one blended colour, so show that
        // instead of pretending the hardware has eight independent pixels.
        else -> IntArray(Effects.PIXELS) { single }
    }
    val scheme = MaterialTheme.colorScheme
    val perPixel = backend == "Root"

    val pulse by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "haloAlpha",
    )

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(6.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) / 2f * 0.72f
            val dot = radius * 0.155f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.13f * pulse),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius * 1.35f,
                ),
                radius = radius * 1.35f,
                center = center,
            )

            drawCircle(
                color = scheme.outlineVariant.copy(alpha = 0.55f),
                radius = radius,
                center = center,
                style = Stroke(width = dot * 0.42f),
            )

            for (i in 0 until 8) {
                val angle = Math.toRadians((i * 45 - 90).toDouble())
                val pos = Offset(
                    center.x + (cos(angle) * radius).toFloat(),
                    center.y + (sin(angle) * radius).toFloat(),
                )
                val argb = colors.getOrElse(i) { 0 }
                val color = Color(argb)
                val lit = (argb and 0xFFFFFF) != 0
                if (lit) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = 0.42f),
                                color.copy(alpha = 0f),
                            ),
                            center = pos,
                            radius = dot * 2.3f,
                        ),
                        radius = dot * 2.3f,
                        center = pos,
                    )
                }
                drawCircle(
                    color = if (lit) color else scheme.surfaceContainerHighest,
                    radius = dot,
                    center = pos,
                )
                drawCircle(
                    color = Color.White.copy(alpha = if (lit) 0.22f else 0.05f),
                    radius = dot * 0.55f,
                    center = pos - Offset(dot * 0.18f, dot * 0.18f),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                when {
                    overlay != null && config.notifStyle != app.aura.ring.NotifStyle.EFFECT ->
                        "NOTIFICATION"
                    else -> config.mode.label.uppercase()
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    !perPixel && running -> "RING"
                    config.mode == RingMode.BATTERY ||
                        (config.chargingArc && battery.charging) ->
                        if (battery.level >= 0) "${battery.level}%" else "ARC"
                    config.mode == RingMode.SPECTRUM -> "LIVE"
                    config.mode == RingMode.NOTIFICATIONS_ONLY -> "EVENTS"
                    config.mode == RingMode.OFF -> "OFF"
                    else -> "8 PX"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                when {
                    !running -> "preview"
                    backend == "Root" -> "per-pixel"
                    backend == "Shizuku" -> "single colour"
                    else -> "waiting"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            if (config.music && audio.hasSignal && audio.bpm > 0) {
                Text(
                    "${audio.bpm} BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
    }
}

/** Compact version used at the top of the notification list. */
@Composable
fun MiniRing(colors: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.size(44.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * 0.74f
        val dot = radius * 0.26f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45 - 90).toDouble())
            val pos = Offset(
                center.x + (cos(angle) * radius).toFloat(),
                center.y + (sin(angle) * radius).toFloat(),
            )
            drawCircle(Color(colors.getOrElse(i) { 0 }), dot, pos)
        }
    }
}
