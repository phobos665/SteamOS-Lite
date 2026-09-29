package com.steamoslite.stores

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.stores.epic.EpicAuthManager
import com.steamoslite.stores.epic.EpicManager
import com.steamoslite.stores.gog.GOGAuthManager
import com.steamoslite.stores.gog.GOGManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the UI does with GOG and Epic: sign in and out, refresh, uninstall, launch. */
object Stores {
    fun signedIn(context: Context, store: Store): Boolean = when (store) {
        Store.GOG -> GOGAuthManager.hasStoredCredentials(context)
        Store.EPIC -> EpicAuthManager.hasStoredCredentials(context)
    }

    suspend fun signIn(context: Context, store: Store, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        when (store) {
            Store.GOG -> GOGAuthManager.authenticateWithCode(context, code).map { }
            Store.EPIC -> EpicAuthManager.authenticateWithCode(context, code).map { }
        }
    }

    suspend fun signOut(context: Context, store: Store) = withContext(Dispatchers.IO) {
        when (store) {
            Store.GOG -> GOGAuthManager.clearStoredCredentials(context)
            Store.EPIC -> EpicAuthManager.clearStoredCredentials(context)
        }
        StoreLibrary(context, store).clear()
    }

    suspend fun refresh(context: Context, store: Store, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<List<StoreGame>> =
        withContext(Dispatchers.IO) {
            val library = StoreLibrary(context, store)
            val result = when (store) {
                Store.GOG -> GOGManager.refreshLibrary(context, library.games(), onProgress)
                Store.EPIC -> EpicManager.refreshLibrary(context, onProgress)
            }
            result.onSuccess { library.save(it) }
        }

    suspend fun uninstall(context: Context, game: StoreGame) = withContext(Dispatchers.IO) {
        val library = StoreLibrary(context, game.store)
        library.installation(game.id)?.let { inst ->
            File(LinuxRuntime.rootDir(context), inst.guestDir.removePrefix("/")).deleteRecursively()
        }
        library.setInstalled(game.id, null)
        StoreShortcuts.sync(context)
    }

    /** The id Steam starts the game's shortcut with, which its game settings are kept under. */
    fun steamAppId(game: StoreGame) = StoreShortcuts.shortId(StoreShortcuts.key(game.store, game.id)).toString()

    /** What SessionActivity takes as its app id to start the game's Steam shortcut. */
    fun launchId(game: StoreGame) = StoreShortcuts.launchId(game.store, game.id)
}
