package com.steamoslite.frontend

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.ui.MainActivity
import com.steamoslite.ui.SessionActivity

/**
 * What a frontend starts: works out which game it asked for (FrontendExport.appIdOf) and starts
 * SteamOS straight into it, exactly as tapping the game on Home does. Shows nothing of its own.
 */
class FrontendLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tappedAt = System.currentTimeMillis()
        val appId = FrontendExport.appIdOf(this, intent)
        Log.i(TAG, "frontend launch ${intent.action} ${intent.dataString ?: ""} -> app ${appId ?: "none"}")
        when {
            !LinuxRuntime.isInstalled(this) -> {
                Toast.makeText(this, "Install the SteamOS runtime first.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            appId == null -> {
                Toast.makeText(this, "SteamOS Lite could not tell which game to start.", Toast.LENGTH_LONG).show()
            }
            else -> startActivity(
                Intent(this, SessionActivity::class.java)
                    .putExtra(SessionActivity.EXTRA_APP_ID, appId)
                    .putExtra(SessionActivity.EXTRA_TAPPED_AT, tappedAt)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "FrontendLaunch"
    }
}
