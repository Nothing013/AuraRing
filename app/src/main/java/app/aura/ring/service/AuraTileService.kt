package app.aura.ring.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.R

class AuraTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        AppState.init(this)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        AppState.init(this)
        if (AppState.running.value) {
            AppState.setRunning(false)
            runCatching {
                startService(
                    Intent(this, RingService::class.java).setAction(RingService.ACTION_STOP)
                )
            }
        } else {
            runCatching {
                ContextCompat.startForegroundService(
                    this, Intent(this, RingService::class.java)
                )
                AppState.setRunning(true)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val running = AppState.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = when {
            !running -> "Off"
            AppState.backend.value == "Starting" -> "Starting"
            else -> AppState.backend.value
        }
        tile.updateTile()
    }
}
