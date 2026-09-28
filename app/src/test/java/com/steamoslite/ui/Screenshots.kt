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
                onOpenProtons = if (shareLogs) ({}) else null,
                onOpenSettings = {},
                coverOf = { covers[it.appId] },
            )
        }
    }

    @Test fun setup() = home(RuntimeState.Missing(release))

    @Test fun installing() = home(RuntimeState.Installing("Downloading", 42))

    @Test fun installing_retrying() = home(RuntimeState.Installing("Connection problem, retrying in 8 s (attempt 3)", 42))

    @Test fun setup_resume() = home(RuntimeState.Missing(release, 331_000_000L))

    @Test fun library() = home(RuntimeState.Ready("r9", null), shareLogs = true)

    @Test fun compatibility_tools() = paparazzi.snapshot {
        AppTheme {
            ProtonsScreen(
                state = ProtonsState(
                    engines = listOf("Proton Experimental (ARM64)"),
                    installed = listOf(com.steamoslite.runtime.Protons.Installed("GE-Proton11-7-aarch64", "GE-Proton11-7 (Bannerlator)")),
                    queued = listOf("/data/user/0/com.steamoslite/files/protons/proton-custom-arm64.tar.xz"),
                    catalog = listOf(
                        com.steamoslite.runtime.Protons.CatalogBuild("GE-Proton 11-7", "GE-Proton11-7-aarch64",
                            "GloriousEggroll's build, with its own game patches.", 645_786_140, "ge --tag GE-Proton11-7"),
                        com.steamoslite.runtime.Protons.CatalogBuild("Proton CachyOS 11.0 (2026-07-03)", "proton-cachyos-11.0-20260703-slr-arm64",
                            "CachyOS's build, heavily patched for performance.", 339_912_088, "cachyos --tag cachyos-11.0-20260703-slr"),
                    ),
                    catalogLoading = false,
                ),
                onBack = {}, onImport = {}, onInstall = {}, onCancel = {}, onRemove = {},
            )
        }
    }

    @Test fun settings() = paparazzi.snapshot {
        AppTheme {
            SettingsScreen(
                SettingsState(
                    nativeResolution = com.steamoslite.runtime.Settings.Resolution(1920, 1080),
                    refreshRates = listOf(120, 90, 60),
                    fex = ComponentPick(listOf("2507", "2508", "2511", "2512", "2601", "2603", "2604", "2605"), selected = "2605"),
                    dxvk = ComponentPick(listOf("1.11.1-sarek", "2.4.1-gplasync", "2.6.1-gplasync", "async-1.10.3")),
                ),
                onBack = {},
            ) {}
        }
    }

    /** A phone on its side: 2400 x 1080 at xxhdpi is only 360 dp tall. */
    @Test fun library_phone_landscape() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_5.copy(
                screenWidth = 2400,
                screenHeight = 1080,
                density = Density.XXHIGH,
                orientation = ScreenOrientation.LANDSCAPE,
            ),
        )
        home(RuntimeState.Ready("r9", null), shareLogs = true)
    }

    @Test fun game_details() = paparazzi.snapshot {
        val game = games[0].first
        fun ach(i: Int, unlocked: Boolean, hidden: Boolean = false) = com.steamoslite.util.SteamFiles.Achievement(
            "ACH_$i", listOf("First Steps", "Frontier Scout", "Long Haul", "Night Owl", "Completionist")[i],
            listOf("Finish the prologue.", "Map every outpost.", "Travel 1,000 km.", "Play after midnight.", "Earn every other achievement.")[i],
            hidden, unlocked, if (unlocked) 1_758_000_000L + i * 86_400L else 0L, null, null,
        )
        AppTheme {
            GameDetailsScreen(
                game,
                com.steamoslite.games.GameDetails(
                    game, null,
                    com.steamoslite.util.SteamFiles.Playtime(754, 1_758_900_000),
                    com.steamoslite.util.SteamFiles.AppInfo("Aurora Frontier", "Northlight", "Northlight", 1_700_000_000, 88, 3, "full", 92),
                    listOf(ach(0, true), ach(1, true), ach(2, false), ach(3, false, hidden = true), ach(4, false)),
                ),
                com.steamoslite.games.StoreDetails(
                    "Chart a frozen frontier with your crew, one outpost at a time.", listOf("Adventure", "Exploration"), emptyList(),
                ),
                onBack = {}, onPlay = {}, onPin = {},
                image = { _, _ -> null },
            )
        }
    }

    private val storeGames = games.take(6).mapIndexed { i, (_, name, _) ->
        com.steamoslite.stores.StoreGame(com.steamoslite.stores.Store.GOG, (2000 + i).toString(), name, developer = "Northlight",
            description = "Chart a frozen frontier with your crew, one outpost at a time.", downloadSize = 12_400_000_000L)
    }

    private fun storeTab(state: com.steamoslite.stores.Store.() -> StoreTabState) = paparazzi.snapshot {
        AppTheme {
            HomeScreen(
                state = RuntimeState.Ready("r9", null),
                games = emptyList(),
                onInstall = {}, onCancel = {}, onRetry = {}, onLaunch = {}, onOpenSettings = {},
                tab = com.steamoslite.stores.Store.GOG,
                onSelectTab = {},
                storeTab = com.steamoslite.stores.Store.GOG.state(),
                storeCoverOf = { g -> games.firstOrNull { it.second == g.title }?.let { covers[it.first.appId] } },
            )
        }
    }

    @Test fun gog_signed_out() = storeTab { StoreTabState(this, signedIn = false) }

    @Test fun gog_library() = storeTab {
        StoreTabState(
            this, signedIn = true, games = storeGames, installed = setOf("2000"),
            downloads = mapOf("2001" to com.steamoslite.stores.StoreDownload(this, "2001", storeGames[1].title, fraction = 0.37f, stage = "Downloading")),
        )
    }

    @Test fun store_game() = paparazzi.snapshot {
        AppTheme {
            StoreGameScreen(
                storeGames[1].copy(dlc = listOf(com.steamoslite.stores.StoreDlc("d1", "Frozen Depths"), com.steamoslite.stores.StoreDlc("d2", "Outpost Pack"))), null,
                com.steamoslite.stores.StoreDownload(com.steamoslite.stores.Store.GOG, "2001", storeGames[1].title, fraction = 0.37f, stage = "Downloading"),
                onBack = {}, onPlay = {}, onInstall = {}, onCancel = {}, onUninstall = {},
                image = { _, _ -> null },
            )
        }
    }

    @Test fun library_update_available() = home(RuntimeState.Ready("r8", release))

    @Test fun library_empty() = home(RuntimeState.Ready("r9", null), withGames = false)

    @Test fun install_failed() =
        home(RuntimeState.Failed("The install did not finish. Check the connection and free space, then try again."))

    @Test fun session_loading() = paparazzi.snapshot(
        SessionActivity.loadingView(paparazzi.context).apply {
            text = "Starting SteamOS…\n\ndownloading Steam: bins_linuxarm64 (3/9)"
        },
    )

    @Test fun session_quick_menu() = paparazzi.snapshot(
        android.widget.FrameLayout(paparazzi.context).apply {
            setBackgroundColor(0xFF203040.toInt())
            addView(QuickMenu(paparazzi.context).apply {
                setItems("SteamOS", listOf(
                    QuickMenu.Item({ "Show keyboard" }) {},
                    QuickMenu.Item({ "Exit SteamOS" }) {},
                    QuickMenu.Item({ "Close menu" }) {},
                ))
                open()
            })
        },
    )

    @Test fun session_on_screen_controller() = paparazzi.snapshot(
        android.widget.FrameLayout(paparazzi.context).apply {
            setBackgroundColor(0xFF203040.toInt())
            addView(com.steamoslite.input.OnScreenController(paparazzi.context) {})
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
