package com.fileapex

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fileapex.di.FileApexServices
import com.fileapex.platform.FileApexAndroidBootstrap

class ClipboardPushTileService : TileService() {
    override fun onStartListening() {
        val enabled = runCatching {
            FileApexAndroidBootstrap.ensureInitialized(this) &&
                FileApexServices.settings.clipboardSharingEnabled.value
        }.getOrDefault(false)
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        unlockAndRun {
            val intent = Intent(this, ClipboardPushActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
