package app.aura.ring.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.aura.ring.NotifApp
import app.aura.ring.NotifPattern
import app.aura.ring.NotifStore
import app.aura.ring.NotifStyle
import app.aura.ring.RingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val system: Boolean,
)

@Composable
fun AppsScreen(
    config: RingConfig,
    onConfig: ((RingConfig) -> RingConfig) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val configured by NotifStore.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val listenerEnabled by produceState(
        initialValue = context.notificationAccess()
    ) {
        while (true) {
            value = context.notificationAccess()
            delay(2_000)
        }
    }

    val entries by produceState<List<AppEntry>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.applicationInfo }
            val map = LinkedHashMap<String, AppEntry>()
            for (info in launchable) {
                val pkg = info.packageName
                if (pkg == context.packageName) continue
                map[pkg] = info.toEntry(pm, pkg)
            }
            for (pkg in NotifStore.apps.value.keys) {
                if (map.containsKey(pkg)) continue
                val info = runCatching {
                    pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                }.getOrNull() ?: continue
                map[pkg] = info.toEntry(pm, pkg)
            }
            map.values.sortedBy { it.label.lowercase() }
        }
    }

    val filtered = entries.filter { entry ->
        (showSystem || !entry.system) &&
            (query.isBlank() ||
                entry.label.contains(query, true) ||
                entry.pkg.contains(query, true))
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column {
                Text(
                    "App rhythms",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
                Text(
                    "${configured.values.count { it.enabled }} apps can light the ring",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (listenerEnabled) {
                    scheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    scheme.error.copy(alpha = 0.12f)
                },
                border = BorderStroke(
                    1.dp,
                    if (listenerEnabled) scheme.primary.copy(alpha = 0.3f)
                    else scheme.error.copy(alpha = 0.4f),
                ),
                onClick = {
                    if (!listenerEnabled) {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (listenerEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (listenerEnabled) scheme.primary else scheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (listenerEnabled) "Notification access granted" else "Notification access needed",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            if (listenerEnabled) {
                                "Ongoing and media notifications are ignored."
                            } else {
                                "Tap to allow Aura Ring to read notifications."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            ToggleRow(
                title = "App notifications",
                subtitle = "Master switch for every per-app rhythm",
                icon = Icons.Rounded.NotificationsActive,
                checked = config.notifications,
                onCheckedChange = { v -> onConfig { it.copy(notifications = v) } },
            )
        }

        if (config.notifStyle == NotifStyle.OFF) {
            item {
                Text(
                    "Notification style is set to Off on the Ring tab, so these colours will not be shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Show system apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary,
                    ),
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    "No apps match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        items(filtered, key = { it.pkg }) { entry ->
            val app = configured[entry.pkg] ?: NotifApp(entry.pkg)
            AppCard(
                entry = entry,
                app = app,
                defaultSeconds = config.notifSeconds,
                onUpdate = { block -> NotifStore.update(entry.pkg, block) },
            )
        }
    }
}

@Composable
private fun AppCard(
    entry: AppEntry,
    app: NotifApp,
    defaultSeconds: Int,
    onUpdate: ((NotifApp) -> NotifApp) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer.copy(alpha = 0.8f),
        border = BorderStroke(
            1.dp,
            if (app.enabled) scheme.primary.copy(alpha = 0.35f) else scheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = entry.icon
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        Text(
                            entry.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        entry.pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Switch(
                    checked = app.enabled,
                    onCheckedChange = { v -> onUpdate { it.copy(enabled = v) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary,
                        uncheckedThumbColor = scheme.outline,
                        uncheckedTrackColor = scheme.surfaceContainerHighest,
                        uncheckedBorderColor = scheme.outline,
                    ),
                )
            }

            AnimatedVisibility(app.enabled) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    PillRow(
                        options = NotifPattern.entries,
                        selected = app.pattern,
                        label = { it.label },
                        onSelect = { p -> onUpdate { it.copy(pattern = p) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    ColorPicker(
                        label = "Notification colour",
                        color = app.color,
                        onColor = { c -> onUpdate { it.copy(color = c) } },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Length",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (app.seconds == 0) "Default (${defaultSeconds}s)" else "${app.seconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurface,
                        )
                    }
                    Slider(
                        value = app.seconds.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(seconds = v.roundToInt()) } },
                        valueRange = 0f..15f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.primary,
                            activeTrackColor = scheme.primary,
                            inactiveTrackColor = scheme.surfaceContainerHighest,
                        ),
                    )
                }
            }
        }
    }
}

private fun ApplicationInfo.toEntry(pm: PackageManager, pkg: String) = AppEntry(
    pkg = pkg,
    label = runCatching { pm.getApplicationLabel(this).toString() }.getOrDefault(pkg),
    icon = loadIcon(pm, pkg),
    system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
)

private fun loadIcon(pm: PackageManager, pkg: String): ImageBitmap? = runCatching {
    val drawable: Drawable = pm.getApplicationIcon(pkg)
    drawable.toBitmap(width = 96, height = 96).asImageBitmap()
}.getOrNull()

private fun android.content.Context.notificationAccess(): Boolean =
    Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        ?.contains(packageName) == true
