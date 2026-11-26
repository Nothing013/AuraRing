package app.aura.ring.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.AudioSource
import app.aura.ring.BatteryInfo
import app.aura.ring.FrameRate
import app.aura.ring.NotifStyle
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import app.aura.ring.ScreenPolicy
import app.aura.ring.core.Palettes
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
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
    val audio by AppState.audio.collectAsState()

    val enableMusic = {
        onConfig { it.copy(music = true) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableMusic()
        else Toast.makeText(context, "Microphone access is needed for audio capture", Toast.LENGTH_LONG).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = if (running) 0.20f else 0.06f),
                                scheme.secondary.copy(alpha = if (running) 0.14f else 0.04f),
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
                                if (running) scheme.primary else scheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = if (running) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "Ring is active" else "Ring is off",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                        Text(
                            when {
                                !running -> "Start to connect to the ring"
                                backend == "Root" -> "Root · full 8-pixel control"
                                backend == "Shizuku" -> "Shizuku · whole-ring colour"
                                backend == "Starting" -> "Connecting…"
                                else -> "No backend — grant root or Shizuku"
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
                    if (config.mode == RingMode.NOTIFICATIONS_ONLY) {
                        Text(
                            "The ring stays dark. Notifications, automations and music still light it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.secondary,
                        )
                    }
                    if (config.mode == RingMode.SPECTRUM && !config.music) {
                        Text(
                            "Spectrum is dark until audio-reactive is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.secondary,
                        )
                    }
                }
            }
        }

        item {
            val paletteMode = config.mode in setOf(
                RingMode.RAINBOW,
                RingMode.SPECTRUM,
                RingMode.FIRE,
                RingMode.AURORA,
                RingMode.NOTIFICATIONS_ONLY,
            )
            val secondColor = config.mode in setOf(
                RingMode.SPLIT,
                RingMode.SPIN,
                RingMode.SPECTRUM,
            )
            SectionCard(title = "Colour & palette", icon = Icons.Rounded.Palette) {
                ColorPicker(
                    label = when (config.mode) {
                        RingMode.SPECTRUM -> "Low band colour"
                        RingMode.SPLIT -> "Half A"
                        RingMode.SPIN -> "Orbiting spot A"
                        else -> "Primary colour"
                    },
                    color = config.color,
                    onColor = { c -> onConfig { it.copy(color = c) } },
                )
                AnimatedVisibility(secondColor) {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        ColorPicker(
                            label = when (config.mode) {
                                RingMode.SPECTRUM -> "High band colour"
                                RingMode.SPLIT -> "Half B"
                                else -> "Orbiting spot B"
                            },
                            color = config.color2,
                            onColor = { c -> onConfig { it.copy(color2 = c) } },
                        )
                    }
                }
                AnimatedVisibility(paletteMode) {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Palette",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Palettes.ALL.forEach { pal ->
                                Chip(
                                    label = pal.label,
                                    selected = config.palette == pal.id,
                                    accent = scheme.secondary,
                                    onClick = { onConfig { it.copy(palette = pal.id) } },
                                )
                            }
                        }
                        Text(
                            if (config.palette == Palettes.CUSTOM) {
                                "Custom fades between the two colours above."
                            } else {
                                "Each palette blends a set of colours evenly around the ring."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
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

                LabelledSlider(
                    label = "Smoothing",
                    value = config.smoothing,
                    range = 0f..1f,
                    display = if (config.smoothing < 0.02f) "Off"
                    else "${(config.smoothing * 100).roundToInt()}%",
                ) { v -> onConfig { it.copy(smoothing = v) } }
            }
        }

        item {
            SectionCard(
                title = "Music",
                icon = Icons.Rounded.MusicNote,
                accent = scheme.secondary,
            ) {
                Column {
                    ToggleRow(
                        title = "Audio reactive",
                        subtitle = "Light follows the beat, bass and treble",
                        icon = Icons.Rounded.GraphicEq,
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

                    AnimatedVisibility(config.music) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Spacer(Modifier.height(6.dp))
                            PillRow(
                                options = AudioSource.entries,
                                selected = config.audioSource,
                                label = { it.label },
                                onSelect = { s -> onConfig { it.copy(audioSource = s) } },
                            )
                            Text(
                                if (config.audioSource == AudioSource.SYSTEM) {
                                    "Reads the phone's output mix, so it follows every app."
                                } else {
                                    "Uses the microphone. Useful when the output mix is blocked."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )

                            ToggleRow(
                                title = "Automatic sensitivity",
                                subtitle = "Keeps quiet and loud tracks at a similar level",
                                icon = Icons.Rounded.Speed,
                                checked = config.autoSensitivity,
                                accent = scheme.secondary,
                                onCheckedChange = { v -> onConfig { it.copy(autoSensitivity = v) } },
                            )
                            if (!config.autoSensitivity) {
                                LabelledSlider(
                                    label = "Sensitivity",
                                    value = config.sensitivity,
                                    range = 0.3f..2.5f,
                                    display = "%.2fx".format(config.sensitivity),
                                ) { v -> onConfig { it.copy(sensitivity = v) } }
                            }

                            BandMeter(audio.bands)
                            Text(
                                when {
                                    audio.error != null -> audio.error!!
                                    !running -> "Start the ring to capture audio"
                                    audio.hasSignal -> buildString {
                                        append(audio.source)
                                        if (audio.bpm > 0) append(" · ${audio.bpm} BPM")
                                        append(" · ${(audio.level * 100).roundToInt()}%")
                                    }
                                    else -> "Listening… start playback"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (audio.error != null) scheme.error else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Notifications",
                icon = Icons.Rounded.NotificationsActive,
                accent = scheme.tertiary,
            ) {
                Column {
                    ToggleRow(
                        title = "Light up on notifications",
                        subtitle = "Per-app colours and patterns from the Apps tab",
                        icon = Icons.Rounded.NotificationsActive,
                        checked = config.notifications,
                        accent = scheme.tertiary,
                        onCheckedChange = { v -> onConfig { it.copy(notifications = v) } },
                    )
                    AnimatedVisibility(config.notifications) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillRow(
                                options = NotifStyle.entries,
                                selected = config.notifStyle,
                                label = { it.label },
                                onSelect = { style -> onConfig { it.copy(notifStyle = style) } },
                            )
                            Text(
                                config.notifStyle.blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            if (config.notifStyle == NotifStyle.FLASH) {
                                LabelledSlider(
                                    label = "Default length",
                                    value = config.notifSeconds.toFloat(),
                                    range = 1f..15f,
                                    display = "${config.notifSeconds}s",
                                ) { v -> onConfig { it.copy(notifSeconds = v.roundToInt()) } }
                            }
                        }
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
                        title = "Dim when the screen is off",
                        subtitle = "A calmer glow at night",
                        icon = Icons.Rounded.DarkMode,
                        checked = config.dimWhenScreenOff,
                        onCheckedChange = { v -> onConfig { it.copy(dimWhenScreenOff = v) } },
                    )
                    ToggleRow(
                        title = "Start on boot",
                        subtitle = "Bring the ring back after a reboot",
                        icon = Icons.Rounded.LightMode,
                        checked = config.startOnBoot,
                        onCheckedChange = { v -> onConfig { it.copy(startOnBoot = v) } },
                    )
                }
            }
        }

        item {
            SectionCard(title = "Power", icon = Icons.Rounded.Speed) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Screen policy",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    PillRow(
                        options = ScreenPolicy.entries,
                        selected = config.screenPolicy,
                        label = { it.label },
                        onSelect = { p -> onConfig { it.copy(screenPolicy = p) } },
                    )
                    Text(
                        config.screenPolicy.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Frame rate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    PillRow(
                        options = FrameRate.entries,
                        selected = config.frameRate,
                        label = { it.label },
                        onSelect = { f -> onConfig { it.copy(frameRate = f) } },
                    )
                    ToggleRow(
                        title = "Battery saver mode",
                        subtitle = "Drop to the Eco frame rate when the phone is saving power",
                        icon = Icons.Rounded.Bolt,
                        checked = config.autoEco,
                        onCheckedChange = { v -> onConfig { it.copy(autoEco = v) } },
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
                            "Per-app rhythms",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            "Pick a colour, pattern and length for each app",
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
        backend == "Starting" -> "Starting" to scheme.onSurfaceVariant
        else -> "No access" to scheme.error
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
