package com.levver.vpn

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent
import android.os.Build

class QuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("levver_runtime", MODE_PRIVATE)
        val active = prefs.getBoolean("vpn_active", false)
        if (active) {
            startService(Intent(this, LevverVpnService::class.java).setAction(LevverVpnService.ACTION_STOP))
        } else {
            val config = prefs.getString("last_config", null)
            if (config.isNullOrBlank()) {
                openApp()
                return
            }
            if (VpnService.prepare(this) != null) {
                openApp()
                return
            }
            val service = Intent(this, LevverVpnService::class.java)
                .setAction(LevverVpnService.ACTION_START)
                .putExtra(LevverVpnService.EXTRA_CONFIG, config)
            androidx.core.content.ContextCompat.startForegroundService(this, service)
        }
        qsTile?.state = if (active) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qsTile?.label = if (active) "LEVVER VPN" else "LEVVER: запуск"
        qsTile?.updateTile()
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending)
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }

    private fun refreshTile() {
        val active = getSharedPreferences("levver_runtime", MODE_PRIVATE)
            .getBoolean("vpn_active", false)
        qsTile?.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.label = "LEVVER VPN"
        qsTile?.contentDescription = if (active) "VPN включён" else "VPN выключен"
        qsTile?.updateTile()
    }
}
