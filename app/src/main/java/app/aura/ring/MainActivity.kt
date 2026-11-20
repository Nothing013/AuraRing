package app.aura.ring

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aura.ring.service.RingService
import app.aura.ring.ui.AppsScreen
import app.aura.ring.ui.AuraTheme
import app.aura.ring.ui.HomeScreen
import app.aura.ring.ui.SettingsScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        NotifStore.load(this)
        enableEdgeToEdge()
        setContent {
            AuraTheme {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val shizukuListener = remember {
                    Shizuku.OnRequestPermissionResultListener { _, grant ->
                        Toast.makeText(
                            context,
                            if (grant == PackageManager.PERMISSION_GRANTED) {
                                "Shizuku granted — restart the ring to use it"
                            } else {
                                "Shizuku permission denied"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                DisposableEffect(Unit) {
                    runCatching {
                        Shizuku.addRequestPermissionResultListener(shizukuListener)
                    }
                    onDispose {
                        runCatching {
                            Shizuku.removeRequestPermissionResultListener(shizukuListener)
                        }
                    }
                }

                AuraRoot(context)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Ring", Icons.Rounded.Home),
    APPS("Apps", Icons.Rounded.Apps),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
private fun AuraRoot(context: Context) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val config by AppState.config.collectAsState()
    val battery by AppState.battery.collectAsState()
    val running by AppState.running.collectAsState()
    val backend by AppState.backend.collectAsState()
    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.background,
                        scheme.surfaceContainerLowest,
                        scheme.background,
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    BottomBar(
                        selected = tab,
                        onSelect = { tab = it },
                    )
                }
            },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .statusBarsPadding()
            ) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        config = config,
                        battery = battery,
                        running = running,
                        backend = backend,
                        onConfig = { block -> AppState.update(block) },
                        onRunningChange = { on ->
                            if (on) startRing(context) else stopRing(context)
                        },
                        onOpenApps = { tab = Tab.APPS },
                    )

                    Tab.APPS -> AppsScreen(
                        config = config,
                        onConfig = { block -> AppState.update(block) },
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        onRestartRing = { restartRing(context) },
                        onStopRing = { stopRing(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = scheme.surfaceContainer.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, scheme.outlineVariant.copy(alpha = 0.9f)
        ),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) scheme.primary.copy(alpha = 0.16f) else Color.Transparent
                        )
                        .clickable { onSelect(tab) }
                        .padding(vertical = 12.dp)
                        .animateContentSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) scheme.primary else scheme.onSurfaceVariant,
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun startRing(context: Context) {
    runCatching {
        ContextCompat.startForegroundService(
            context, Intent(context, RingService::class.java)
        )
    }.onFailure {
        Toast.makeText(context, "Cannot start service: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

private fun stopRing(context: Context) {
    runCatching {
        context.startService(
            Intent(context, RingService::class.java).setAction(RingService.ACTION_STOP)
        )
    }
}

private fun restartRing(context: Context) {
    stopRing(context)
    Handler(Looper.getMainLooper()).postDelayed({
        startRing(context)
    }, 900)
}
