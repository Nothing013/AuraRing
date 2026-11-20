package app.aura.ring.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
    contentPadding: Int = 18,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.85f)),
    ) {
        Column(Modifier.padding(contentPadding.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = accent ?: scheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background((accent ?: scheme.primary).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent ?: scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                uncheckedThumbColor = scheme.outline,
                uncheckedTrackColor = scheme.surfaceContainerHighest,
                uncheckedBorderColor = scheme.outline,
            ),
        )
    }
}

@Composable
fun <T> PillRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) scheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.22f) else scheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.75f) else scheme.outlineVariant,
        ),
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else scheme.onSurfaceVariant,
        )
    }
}

private val presets = intArrayOf(
    0xFF33E1FF.toInt(),
    0xFF7DF9FF.toInt(),
    0xFF4ADE80.toInt(),
    0xFFFFD166.toInt(),
    0xFFFF8A5B.toInt(),
    0xFFFF2D95.toInt(),
    0xFFB388FF.toInt(),
    0xFFFFFFFF.toInt(),
)

/** Hue strip plus a row of common presets. Saturation and value are fixed. */
@Composable
fun ColorPicker(
    label: String,
    color: Int,
    onColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var width by remember { mutableFloatStateOf(1f) }
    val hsv = remember(color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(color, out)
        if (out[1] < 0.15f) out[1] = 0.9f
        out[2] = 1f
        out
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "#%06X".format(color and 0xFFFFFF),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onGloballyPositioned { width = it.size.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onColor(hueAt(down.position.x, width, hsv))
                        drag(down.id) { change ->
                            onColor(hueAt(change.position.x, width, hsv))
                            change.consume()
                        }
                    }
                },
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            ) {
                val hues = (0..12).map { i ->
                    Color.hsv((i * 30f) % 360f, 0.95f, 1f)
                }
                drawRoundRect(
                    brush = Brush.horizontalGradient(hues, startX = 0f, endX = size.width),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
                val x = (hsv[0] / 360f) * size.width
                drawCircle(
                    color = Color.White,
                    radius = size.height * 0.42f,
                    center = Offset(x.coerceIn(6f, size.width - 6f), size.height / 2f),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = size.height * 0.42f,
                    center = Offset(x.coerceIn(6f, size.width - 6f), size.height / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                val selected = (preset and 0xFFFFFF) == (color and 0xFFFFFF)
                Box(
                    modifier = Modifier
                        .size(if (selected) 30.dp else 26.dp)
                        .clip(CircleShape)
                        .background(Color(preset))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) scheme.onSurface else Color.White.copy(alpha = 0.2f),
                            shape = CircleShape,
                        )
                        .clickable { onColor(preset) },
                )
            }
        }
    }
}

private fun hueAt(x: Float, width: Float, base: FloatArray): Int {
    val hue = (x / width).coerceIn(0f, 0.999f) * 360f
    return android.graphics.Color.HSVToColor(floatArrayOf(hue, base[1], base[2]))
}

/** Small equaliser used on the home screen while audio mode is on. */
@Composable
fun BandMeter(bands: FloatArray, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(
        modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        val count = 8
        val gap = size.width * 0.02f
        val barWidth = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val v = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val h = (size.height * (0.08f + 0.92f * v))
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(scheme.primary, scheme.secondary),
                    startY = size.height,
                    endY = size.height - h,
                ),
                topLeft = Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Key/value line used on the settings screen. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun Int.luma(): Float {
    val c = Color(this)
    return 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
}

fun Int.toComposeColor(): Color = Color(this)

fun Color.toArgbInt(): Int = toArgb()
