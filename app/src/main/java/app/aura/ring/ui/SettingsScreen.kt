package app.aura.ring.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aura.ring.AppState
import app.aura.ring.BuildConfig
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

private data class ShizukuState(
    val running: Boolean = false,
    val granted: Boolean = false,
)

@Composable
fun SettingsScreen(
    onRestartRing: () -> Unit,
    onStopRing: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val backend by AppState.backend.collectAsState()
    val running by AppState.running.collectAsState()

    val shizuku by produceState(ShizukuState()) {
        while (true) {
            value = try {
                val alive = Shizuku.pingBinder()
                ShizukuState(
                    running = alive,
                    granted = alive && Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            } catch (_: Throwable) {
                ShizukuState()
            }
            delay(1500)
        }
    }

    val listenerEnabled = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    )?.contains(context.packageName) == true

    val batteryExempt by produceState(false) {
        while (true) {
            value = runCatching {
                context.getSystemService(android.os.PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
            delay(2_000)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
                Text(
                    "Connection, coordination and automation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Connection", icon = Icons.Rounded.Bolt) {
                InfoRow("Backend", if (running) backend else "Stopped")
                InfoRow(
                    "Pixel control",
                    if (running && backend == "Root") "8 independent RGB pixels"
                    else if (running) "Whole-ring colour only"
                    else "—",
                )
                InfoRow("Shizuku service", if (shizuku.running) "Running" else "Not running")
                InfoRow("Shizuku grant", if (shizuku.granted) "Granted" else "Not granted")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onRestartRing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restart ring")
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                Shizuku.requestPermission(0)
                            } catch (t: Throwable) {
                                Toast.makeText(
                                    context,
                                    "Shizuku is not running",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        enabled = shizuku.running && !shizuku.granted,
                    ) {
                        Text(if (shizuku.granted) "Shizuku granted" else "Grant Shizuku")
                    }
                }
            }
        }

        item {
            SectionCard(title = "HyperOS coordination", icon = Icons.Rounded.Info) {
                Text(
                    "With root, Aura takes temporary ownership of the light chip. " +
                        "It sets settings_strip_light_enable=0 while running so HyperOS " +
                        "cannot overwrite frames, then restores your original value when " +
                        "you stop the ring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "With Shizuku, the system strip must stay enabled (value 2). " +
                        "System scenes such as charging or a camera countdown can still " +
                        "override the ring until Aura's next frame.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onStopRing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = scheme.error,
                    ),
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.5f)),
                ) {
                    Text("Stop and hand back to HyperOS")
                }
            }
        }

        item {
            SectionCard(title = "Automation reliability", icon = Icons.Rounded.Bolt) {
                Text(
                    "Android blocks apps from starting a foreground service in the " +
                        "background. Exempting Aura from battery optimisation lets Tasker " +
                        "and notification intents switch the ring on even when the app is " +
                        "closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (batteryExempt) {
                    Text(
                        "Background starts allowed",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Allow background starts")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Notification access", icon = Icons.Rounded.NotificationsActive) {
                Text(
                    if (listenerEnabled) {
                        "Aura can read notification posts and flash the ring for the " +
                            "apps you enabled."
                    } else {
                        "Aura needs notification access before per-app rhythms work."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                ) {
                    Text("Open notification access")
                }
            }
        }

        item {
            SectionCard(title = "Tasker & intents", icon = Icons.Rounded.Terminal) {
                Text(
                    "Aura accepts broadcasts, so Tasker, MacroDroid or adb can drive " +
                        "the ring. Colours can be passed as a hex string to avoid signed " +
                        "decimal conversions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                CommandBlock(
                    title = "Solid colour, 60% brightness",
                    command = "adb shell am broadcast -a app.aura.ring.SET " +
                        "-n app.aura.ring/.tasker.AuraCommandReceiver " +
                        "--es hex \"#33E1FF\" --es mode SOLID --ei brightness 150",
                    context = context,
                )
                Spacer(Modifier.height(10.dp))
                CommandBlock(
                    title = "Notification-style pulse for 5 seconds",
                    command = "adb shell am broadcast -a app.aura.ring.PULSE " +
                        "-n app.aura.ring/.tasker.AuraCommandReceiver " +
                        "--es hex \"#4ADE80\" --es pattern SWEEP --ei duration 5",
                    context = context,
                )
                Spacer(Modifier.height(10.dp))
                CommandBlock(
                    title = "Turn the ring off",
                    command = "adb shell am broadcast -a app.aura.ring.OFF " +
                        "-n app.aura.ring/.tasker.AuraCommandReceiver",
                    context = context,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Extras: mode = OFF, SOLID, RAINBOW, BREATHE, COMET, SPECTRUM, " +
                        "BATTERY · pattern = PULSE, SWEEP, BLINK, BREATHE · colour = " +
                        "hex or signed int.",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "About", icon = Icons.Rounded.Info) {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                InfoRow("Hardware", "AW21024 · 8 RGB pixels")
                InfoRow("Sysfs", "/sys/class/leds/aw21024_led")
                Spacer(Modifier.height(10.dp))
                Text(
                    "The ring is an 8-pixel ring around the camera. Eight pixels is all " +
                        "the chip exposes: arcs, comets and sweeps are quantised to those " +
                        "eight positions, while brightness is smooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open app info")
            }
        }
    }
}

@Composable
private fun CommandBlock(title: String, command: String, context: Context) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy",
                    tint = scheme.primary,
                    modifier = Modifier
                        .padding(4.dp)
                        .width(20.dp)
                        .height(20.dp)
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Aura command", command))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                )
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.primary,
                )
            }
        }
    }
}
