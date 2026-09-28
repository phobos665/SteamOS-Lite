package com.steamoslite.frontend

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.steamoslite.R
import com.steamoslite.games.InstalledGame

/**
 * Home-screen shortcuts for single games: the launcher pins one that starts FrontendLaunchActivity
 * with the game's app id, so it opens straight into the game like a frontend's launch does.
 */
object HomeShortcuts {
    fun supported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut for [game]; false when the launcher cannot. */
    fun pin(context: Context, game: InstalledGame): Boolean {
        if (!supported(context)) return false
        val launch = Intent(FrontendExport.ACTION_LAUNCH)
            .setClassName(context.packageName, FrontendLaunchActivity::class.java.name)
            .putExtra(FrontendExport.EXTRA_APP_ID, game.appId)
        val icon = game.cover?.let { runCatching { iconOf(it.path) }.getOrNull() }
            ?: IconCompat.createWithResource(context, R.drawable.ic_launcher)
        val shortcut = ShortcutInfoCompat.Builder(context, "game-${game.appId}")
            .setShortLabel(game.name.take(24))
            .setLongLabel(game.name)
            .setIcon(icon)
            .setIntent(launch)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** The cover's middle square, scaled to icon size (the capsule is portrait). */
    private fun iconOf(path: String): IconCompat? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        return IconCompat.createWithBitmap(Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true))
    }

    private const val ICON_PX = 256
}
