package com.steamoslite.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.steamoslite.games.SteamLibrary
import com.steamoslite.stores.Store
import com.steamoslite.stores.StoreLibrary
import com.steamoslite.stores.Stores
import com.steamoslite.util.RemoteImages
import java.io.File

/** What the loading screen shows for a launch: the game's name, its cover, and art for the backdrop. */
internal data class LaunchArt(val title: String?, val cover: Bitmap?, val backdrop: Bitmap?) {
    companion object {
        /**
         * The art for [appId] - a Steam app id, or the rungameid of a GOG/Epic shortcut - from the
         * library the app already reads. Whatever started the launch (the app, a frontend, a home
         * screen shortcut) only passes the id, so it is looked up here. Blocking; call off the main thread.
         */
        fun resolve(context: Context, appId: String?): LaunchArt {
            appId ?: return LaunchArt(null, null, null)
            runCatching { SteamLibrary.installedGames(context).firstOrNull { it.appId == appId } }.getOrNull()?.let { game ->
                val cover = game.cover?.let { decode(it, 600) }
                val hero = SteamLibrary.hero(context, appId)?.let { decode(it, 960) }
                return LaunchArt(game.name, cover, hero ?: cover)
            }
            for (store in Store.entries) {
                val game = runCatching { StoreLibrary(context, store).games() }.getOrDefault(emptyList())
                    .firstOrNull { Stores.launchId(it) == appId } ?: continue
                val cover = game.coverUrl.takeIf { it.isNotEmpty() }?.let { RemoteImages.load(context, it, 600) }
                val hero = game.heroUrl.takeIf { it.isNotEmpty() }?.let { RemoteImages.load(context, it, 960) }
                return LaunchArt(game.title, cover, hero ?: cover)
            }
            return LaunchArt(null, null, null)
        }

        private fun decode(file: File, maxPx: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
