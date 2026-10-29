package io.github.gkdhide.gkdx

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 通知栏快捷磁贴：不下应用就能启停 Root 模式 */
class RootTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (RootEngine.running) {
            stopService(Intent(this, RootEngineService::class.java))
            setState(Tile.STATE_INACTIVE)
        } else {
            val i = Intent(this, RootEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            setState(Tile.STATE_ACTIVE)
        }
    }

    private fun refresh() {
        qsTile?.let {
            it.state = if (RootEngine.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    private fun setState(state: Int) {
        qsTile?.let {
            it.state = state
            it.updateTile()
        }
    }
}
