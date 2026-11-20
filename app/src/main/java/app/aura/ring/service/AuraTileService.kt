package app.aura.ring.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.aura.ring.AppState

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
            stopRing()
        } else {
            startRing()
        }
        updateTile()
    }

    private fun startRing() {
        runCatching {
            ContextCompat.startForegroundService(
                this, Intent(this, RingService::class.java)
            )
        }
    }

    private fun stopRing() {
        runCatching {
            startService(
                Intent(this, RingService::class.java).setAction(RingService.ACTION_STOP)
            )
        }
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val running = AppState.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Aura Ring"
        tile.subtitle = if (running) AppState.backend.value else "Off"
        tile.updateTile()
    }
}
