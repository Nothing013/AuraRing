package app.aura.ring.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.BatteryInfo
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    config: RingConfig,
    battery: BatteryInfo,
    running: Boolean,
    backend: String,
    onConfig: ((RingConfig) -> RingConfig) -> Unit,
    onRunningChange: (Boolean) -> Unit,
    onOpenApps: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val bands by AppState.bands.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onConfig { it.copy(music = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Aura Ring",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground,
                    )
                    Text(
                        "Camera ring light",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                BackendPill(backend, running)
            }
        }

        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainer.copy(alpha = 0.82f),
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.8f)),
            ) {
                RingPreview(
                    config = config,
                    battery = battery,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }

        item {
            val active = running
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = if (active) 0.20f else 0.06f),
                                scheme.secondary.copy(alpha = if (active) 0.14f else 0.04f),
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) scheme.primary else scheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (active) "Ring is active" else "Ring is off",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                        Text(
                            when (backend) {
                                "Root" -> "Root · full 8-pixel control"
                                "Shizuku" -> "Shizuku · whole-ring colour"
                                else -> "Start to connect to the ring"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = onRunningChange,
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
        }

        item {
            SectionCard(title = "Effect", icon = Icons.Rounded.AutoAwesome) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeGrid(config.mode) { mode -> onConfig { it.copy(mode = mode) } }
                    Text(
                        config.mode.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Colour", icon = Icons.Rounded.Palette) {
                ColorPicker(
                    label = if (config.mode == RingMode.SPECTRUM) "Low band" else "Primary",
                    color = config.color,
                    onColor = { c -> onConfig { it.copy(color = c) } },
                )
                AnimatedVisibility(config.mode == RingMode.SPECTRUM) {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        ColorPicker(
                            label = "High band",
                            color = config.color2,
                            onColor = { c -> onConfig { it.copy(color2 = c) } },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Brightness & motion", icon = Icons.Rounded.Speed) {
                LabelledSlider(
                    label = "Brightness",
                    value = config.brightness.toFloat(),
                    range = 0f..255f,
                    display = "${(config.brightness / 255f * 100).roundToInt()}%",
                ) { v -> onConfig { it.copy(brightness = v.roundToInt()) } }

                if (config.mode != RingMode.OFF) {
                    LabelledSlider(
                        label = "Speed",
                        value = config.speed,
                        range = 0.2f..3f,
                        display = "%.2fx".format(config.speed),
                    ) { v -> onConfig { it.copy(speed = v) } }
                }

                AnimatedVisibility(config.mode == RingMode.BREATHE) {
                    Column {
                        LabelledSlider(
                            label = "Breath length",
                            value = config.breatheMs.toFloat(),
                            range = 800f..6000f,
                            display = "%.1fs".format(config.breatheMs / 1000f),
                        ) { v -> onConfig { it.copy(breatheMs = v.roundToInt()) } }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Behaviour", icon = Icons.Rounded.Bolt) {
                Column {
                    ToggleRow(
                        title = "Charging arc",
                        subtitle = "Battery level fills the ring while charging",
                        icon = Icons.Rounded.BatteryChargingFull,
                        checked = config.chargingArc,
                        onCheckedChange = { v -> onConfig { it.copy(chargingArc = v) } },
                    )
                    ToggleRow(
                        title = "Music spectrum",
                        subtitle = "Eight log-spaced bands, live from the audio mix",
                        icon = Icons.Rounded.MusicNote,
                        checked = config.music,
                        accent = scheme.secondary,
                        onCheckedChange = { v ->
                            if (v && ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                onConfig { it.copy(music = v) }
                            }
                        },
                    )
                    ToggleRow(
                        title = "App notifications",
                        subtitle = "Per-app colours and rhythms, configured on the Apps tab",
                        icon = Icons.Rounded.NotificationsActive,
                        checked = config.notifications,
                        onCheckedChange = { v -> onConfig { it.copy(notifications = v) } },
                    )
                    ToggleRow(
                        title = "Dim when screen is off",
                        subtitle = "Keeps a faint glow instead of full brightness",
                        icon = Icons.Rounded.DarkMode,
                        checked = config.screenOffDim,
                        onCheckedChange = { v -> onConfig { it.copy(screenOffDim = v) } },
                    )
                    ToggleRow(
                        title = "Start on boot",
                        subtitle = "Reconnect the ring after a reboot",
                        icon = Icons.Rounded.LightMode,
                        checked = config.autoStart,
                        onCheckedChange = { v -> onConfig { it.copy(autoStart = v) } },
                    )
                }
            }
        }

        if (config.music) {
            item {
                SectionCard(title = "Live spectrum", icon = Icons.Rounded.GraphicEq) {
                    BandMeter(bands)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (bands.any { it > 0.01f }) {
                            "Capturing system audio"
                        } else {
                            "Waiting for audio — start playback to see bands move"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Surface(
                onClick = onOpenApps,
                shape = MaterialTheme.shapes.medium,
                color = scheme.secondaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = scheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Configure app rhythms",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            "Pick a colour and pattern for each app",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Open",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendPill(backend: String, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when {
        !running -> "Off" to scheme.onSurfaceVariant
        backend == "Root" -> "Root" to scheme.primary
        backend == "Shizuku" -> "Shizuku" to scheme.tertiary
        else -> "Starting" to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeGrid(selected: RingMode, onSelect: (RingMode) -> Unit) {
    val modes = RingMode.entries.filter { it != RingMode.OFF } + RingMode.OFF
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modes.forEach { mode ->
            Chip(
                label = mode.label,
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValue: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                display,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceContainerHighest,
            ),
        )
    }
}
