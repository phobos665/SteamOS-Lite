package com.steamoslite.stores.epic

import android.content.Context
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.stores.StoreGame
import com.steamoslite.stores.sanitizeForFilename
import timber.log.Timber
import java.io.File

object EpicGameLauncher {
    /** The command-line arguments that sign a game in to Epic Games Services for this launch. */
    suspend fun buildLaunchParameters(context: Context, game: StoreGame, languageCode: String = "en-US"): Result<List<String>> {
        return try {
            val token = EpicAuthManager.getGameLaunchToken(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                requiresOwnershipToken = game.requiresOwnershipToken,
            ).getOrElse { return Result.failure(it) }

            val ownershipTokenPath = token.ownershipToken?.let { saveOwnershipToken(context, game, it) }

            val params = mutableListOf(
                "-AUTH_LOGIN=unused",
                "-AUTH_PASSWORD=${token.authCode}",
                "-AUTH_TYPE=exchangecode",
                "-epicapp=${game.id}",
                "-epicenv=Prod",
                "-EpicPortal",
                "-epicusername=${token.displayName.ifBlank { "EpicUser" }}",
                "-epicuserid=${token.accountId}",
                "-epiclocale=$languageCode",
                "-epicsandboxid=${game.namespace}",
            )
            EpicManager.fetchDeploymentId(context, game)?.let { params += "-epicdeploymentid=$it" }
            ownershipTokenPath?.let { params += "-epicovt=$it" }
            EpicManager.fetchAdditionalCommandLine(context, game)?.takeIf { it.isNotBlank() }?.let { params += tokenizeArgs(it) }
            Result.success(params)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to build launch parameters")
            Result.failure(e)
        }
    }

    /**
     * Splits a Windows command line the way CommandLineToArgvW does: double quotes group, adjacent
     * quoted and unquoted runs join into one argument, and single quotes are ordinary characters.
     */
    private fun tokenizeArgs(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDouble = false
        var hasToken = false
        for (c in input) {
            when {
                inDouble -> if (c == '"') inDouble = false else current.append(c)
                c == '"' -> { inDouble = true; hasToken = true }
                c.isWhitespace() -> if (hasToken) {
                    tokens.add(current.toString())
                    current.setLength(0)
                    hasToken = false
                }
                else -> { current.append(c); hasToken = true }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    /**
     * Writes the DRM ownership token where the game can read it and returns its Windows path: the
     * runtime's /root/.bl-epic, which Wine's Z: drive (the Linux root) reaches.
     */
    private fun saveOwnershipToken(context: Context, game: StoreGame, hex: String): String {
        require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.matches(Regex("^[0-9A-Fa-f]+$"))) { "Invalid ownership token" }
        val name = "${game.namespace.sanitizeForFilename()}${game.catalogId.sanitizeForFilename()}.ovt"
        val dir = File(LinuxRuntime.rootDir(context), "root/.bl-epic").apply { mkdirs() }
        File(dir, name).writeBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return "Z:\\root\\.bl-epic\\$name"
    }
}
