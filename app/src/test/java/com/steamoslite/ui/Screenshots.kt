package com.steamoslite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.steamoslite.games.InstalledGame
import com.steamoslite.runtime.RuntimeInstaller
import org.junit.Rule
import org.junit.Test

/**
 * The app's screens as a 1080p handheld (AYN Thor / KONKR Pocket FIT class) shows them. Run
 * `./gradlew recordPaparazziDebug`; the screenshots workflow copies the results to docs/screenshots.
 *
 * Game titles and covers are made up here: the real ones come from Steam's own cache on the device.
 */
class Screenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920,
            screenHeight = 1080,
            xdpi = 367,
            ydpi = 367,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val release = RuntimeInstaller.Release("r9", "", "", 790_907_606L)

    private val games = listOf(
        "Aurora Frontier" to 0xFF2B6CB0.toInt(),
        "Ember Hollow" to 0xFFC05621.toInt(),
        "Glass Tide" to 0xFF2C7A7B.toInt(),
        "Iron Meridian" to null,
        "Lantern Keep" to 0xFF6B46C1.toInt(),
        "Neon Drift" to 0xFFD53F8C.toInt(),
        "Quiet Orbit" to null,
        "Salt & Signal" to 0xFF38A169.toInt(),
        "Thornwall" to 0xFF975A16.toInt(),
        "Velvet Circuit" to 0xFF4A5568.toInt(),
    ).mapIndexed { i, (name, color) -> Triple(InstalledGame((1000 + i).toString(), name, null), name, color) }

    // Lazy: android.graphics only works once the Paparazzi rule has started layoutlib.
    private val covers: Map<String, Bitmap> by lazy {
        games.mapNotNull { (game, name, color) ->
            color?.let { game.appId to fakeCover(name, it) }
        }.toMap()
    }

    private fun home(state: RuntimeState, withGames: Boolean = true, shareLogs: Boolean = false) = paparazzi.snapshot {
        AppTheme {
            HomeScreen(
                state = state,
                games = if (withGames) games.map { it.first } else emptyList(),
                onInstall = {},
                onCancel = {},
                onRetry = {},
                onLaunch = {},
                onShareLogs = if (shareLogs) ({}) else null,
                coverOf = { covers[it.appId] },
            )
        }
    }

    @Test fun setup() = home(RuntimeState.Missing(release))

    @Test fun installing() = home(RuntimeState.Installing("Downloading", 42))

    @Test fun installing_retrying() = home(RuntimeState.Installing("Connection problem, retrying in 8 s (attempt 3)", 42))

    @Test fun setup_resume() = home(RuntimeState.Missing(release, 331_000_000L))

    @Test fun library() = home(RuntimeState.Ready("r9", null), shareLogs = true)

    @Test fun library_update_available() = home(RuntimeState.Ready("r8", release))

    @Test fun library_empty() = home(RuntimeState.Ready("r9", null), withGames = false)

    @Test fun install_failed() =
        home(RuntimeState.Failed("The install did not finish. Check the connection and free space, then try again."))

    @Test fun session_loading() = paparazzi.snapshot(
        SessionActivity.loadingView(paparazzi.context).apply {
            text = "Starting SteamOS…\n\ndownloading Steam: bins_linuxarm64 (3/9)"
        },
    )

    /** A stand-in capsule: a gradient with the title on it, 600x900 like Steam's. */
    private fun fakeCover(title: String, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 900f, color, 0xFF0B0F14.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 600f, 900f, paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 64f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        title.split(' ').forEachIndexed { i, word -> canvas.drawText(word, 300f, 640f + i * 76f, paint) }
        return bitmap
    }
}
