package com.steamoslite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    private fun home(
        state: RuntimeState,
        withGames: Boolean = true,
        shareLogs: Boolean = false,
        running: Boolean = false,
        tabs: Boolean = false,
        split: Boolean = false,
    ) = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            HomeScreen(
                state = state,
                games = if (!withGames) emptyList() else if (split) games.take(4).map { it.first } else games.map { it.first },
                uninstalled = if (!split) emptyList() else games.drop(4).mapIndexed { i, (g) ->
                    com.steamoslite.games.UninstalledGame(g.appId, g.name, null,
                        if (i == 0) com.steamoslite.games.UninstalledGame.Download(4_200_000_000L, 10_000_000_000L) else null)
                },
                onInstall = {},
                onCancel = {},
                onRetry = {},
                onLaunch = {},
                onShareLogs = if (shareLogs) ({}) else null,
                onOpenProtons = if (shareLogs) ({}) else null,
                onOpenSettings = {},
                coverOf = { covers[it.appId] },
                sessionRunning = running,
                onStopSession = {},
                onSelectTab = if (tabs) ({}) else null,
            )
        }
    }

    @Test fun library_steamos_running() = home(RuntimeState.Ready("r9", null), running = true)

    @Test fun setup() = home(RuntimeState.Missing(release))

    @Test fun installing() = home(RuntimeState.Installing("Downloading", 42))

    @Test fun installing_retrying() = home(RuntimeState.Installing("Connection problem, retrying in 8 s (attempt 3)", 42))

    @Test fun setup_resume() = home(RuntimeState.Missing(release, 331_000_000L))

    @Test fun library() = home(RuntimeState.Ready("r9", null), shareLogs = true)

    @Test fun library_not_installed() = home(RuntimeState.Ready("r9", null), shareLogs = true, split = true)

    @Test fun compatibility_tools() = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
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
        AppTheme(stillFrame = true) {
            SettingsScreen(
                SettingsState(
                    nativeResolution = com.steamoslite.runtime.Settings.Resolution(1920, 1080),
                    refreshRates = listOf(120, 90, 60),
                    fex = ComponentPick(listOf("2507", "2508", "2511", "2512", "2601", "2603", "2604", "2605"), selected = "2605"),
                    dxvk = ComponentPick(listOf("1.11.1-sarek", "2.4.1-gplasync", "2.6.1-gplasync", "async-1.10.3")),
                    gpu = com.steamoslite.runtime.VulkanDrivers.Gpu("Adreno 740", com.steamoslite.runtime.VulkanDrivers.Family.A7XX),
                    drivers = listOf(driver),
                    driver = driver.id,
                    driverCatalog = listOf("a6xx", "a8xx").map {
                        com.steamoslite.runtime.VulkanDrivers.CatalogDriver("Turnip-26.3.0-devel-4cf0989083-$it-Linux", "26.3.0-devel-4cf0989083", it, "", 0)
                    },
                ),
                onBack = {},
            ) {}
        }
    }

    @Test fun settings_driver() = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            Box(
                Modifier.fillMaxSize().background(AppColors.background).padding(24.dp),
            ) {
                DriverChoice(
                    SettingsState(
                        gpu = com.steamoslite.runtime.VulkanDrivers.Gpu("Adreno 740", com.steamoslite.runtime.VulkanDrivers.Family.A7XX),
                        drivers = listOf(driver),
                        driver = driver.id,
                        driverCatalog = listOf("a6xx", "a8xx").map {
                            com.steamoslite.runtime.VulkanDrivers.CatalogDriver("Turnip-26.3.0-devel-0203514513-$it-Linux", "26.3.0-devel-0203514513", it, "", 0)
                        } + com.steamoslite.runtime.VulkanDrivers.CatalogDriver("Turnip-26.4.0-devel-1111111111-a7xx-Linux", "26.4.0-devel-1111111111", "a7xx", "", 0),
                    ),
                ) {}
            }
        }
    }

    @Test fun settings_direct3d12() = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            Column(
                Modifier.fillMaxSize().background(AppColors.background).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Choice(
                    "VKD3D-Proton version", "Direct3D 12 on Vulkan.",
                    listOf("", "2.8", "2.13", "2.14.1", "3.0b"), "2.14.1", label = { it.ifEmpty { "Proton's own" } },
                ) {}
                Vkd3dChoices("12_1", "6_0", "", global = null, onLevel = {}, onModel = {}, onConfig = {})
            }
        }
    }

    private val driver = com.steamoslite.runtime.VulkanDrivers.Installed(
        "Turnip-26.3.0-devel-4cf0989083-a7xx-Linux", "Turnip 26.3.0-devel (a7xx)", "a7xx", "1.4.363", java.io.File("icd.json"),
    )

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
        home(RuntimeState.Ready("r9", null), shareLogs = true, tabs = true)
    }

    private fun ach(i: Int, unlocked: Boolean, hidden: Boolean = false) = com.steamoslite.util.SteamFiles.Achievement(
        "ACH_$i", listOf("First Steps", "Frontier Scout", "Long Haul", "Night Owl", "Completionist")[i],
        listOf("Finish the prologue.", "Map every outpost.", "Travel 1,000 km.", "Play after midnight.", "Earn every other achievement.")[i],
        hidden, unlocked, if (unlocked) 1_758_000_000L + i * 86_400L else 0L, null, null,
    )

    private val achievements = listOf(ach(0, true), ach(1, true), ach(2, false), ach(3, false, hidden = true), ach(4, false))
    private val allAchievements = (0..4).map { ach(it, true) }

    @Test fun game_details() = gameDetails(achievements)

    @Test fun game_details_complete() = gameDetails(allAchievements)

    @Test fun game_details_not_installed() = gameDetails(emptyList(), InstallState.NotInstalled)

    @Test fun game_details_downloading() = gameDetails(emptyList(), InstallState.Downloading(0.42f))

    private fun gameDetails(
        list: List<com.steamoslite.util.SteamFiles.Achievement>,
        install: InstallState = InstallState.Installed,
    ) = paparazzi.snapshot {
        val game = games[0].first
        AppTheme(stillFrame = true) {
            GameDetailsScreen(
                game,
                com.steamoslite.games.GameDetails(
                    game, null,
                    com.steamoslite.util.SteamFiles.Playtime(754, 1_758_900_000),
                    com.steamoslite.util.SteamFiles.AppInfo("Aurora Frontier", "Northlight", "Northlight", 1_700_000_000, 88, 3, "full", 92),
                    list,
                ),
                com.steamoslite.games.StoreDetails(
                    "Chart a frozen frontier with your crew, one outpost at a time.", listOf("Adventure", "Exploration"), emptyList(),
                ),
                onBack = {}, onPlay = {}, onPin = {}, onSettings = {},
                image = { _, _ -> null },
                install = install,
            )
        }
    }

    private val storeGames = games.take(6).mapIndexed { i, (_, name, _) ->
        com.steamoslite.stores.StoreGame(com.steamoslite.stores.Store.GOG, (2000 + i).toString(), name, developer = "Northlight",
            description = "Chart a frozen frontier with your crew, one outpost at a time.", downloadSize = 12_400_000_000L)
    }

    private fun storeTab(state: com.steamoslite.stores.Store.() -> StoreTabState) = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
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
        AppTheme(stillFrame = true) {
            StoreGameScreen(
                storeGames[1].copy(dlc = listOf(com.steamoslite.stores.StoreDlc("d1", "Frozen Depths"), com.steamoslite.stores.StoreDlc("d2", "Outpost Pack"))), null,
                com.steamoslite.stores.StoreDownload(com.steamoslite.stores.Store.GOG, "2001", storeGames[1].title, fraction = 0.37f, stage = "Downloading"),
                onBack = {}, onPlay = {}, onInstall = {}, onCancel = {}, onUninstall = {},
                image = { _, _ -> null },
            )
        }
    }

    @Test fun game_settings() = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            GameSettingsScreen(
                GameSettingsState(
                    "Aurora Frontier",
                    com.steamoslite.runtime.GameSettings(fexCore = "2609", fexPreset = com.steamoslite.runtime.Settings.FexPreset.COMPATIBILITY),
                    fex = ComponentPick(listOf("2507", "2508", "2511", "2512", "2601", "2603", "2604", "2605", "2609"), selected = "2605"),
                    dxvk = ComponentPick(listOf("1.11.1-sarek", "2.4.1-gplasync", "2.6.1-gplasync", "async-1.10.3")),
                    drivers = listOf(driver),
                ),
                onBack = {},
            ) {}
        }
    }

    @Test fun library_update_available() = home(RuntimeState.Ready("r8", release))

    @Test fun library_empty() = home(RuntimeState.Ready("r9", null), withGames = false)

    @Test fun install_failed() =
        home(RuntimeState.Failed("The install did not finish. Check the connection and free space, then try again."))

    @Test fun session_loading() = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            LoadingScreen(
                LoadingState(stage = 1, stages = 3, step = "starting the Steam client", detail = "Downloading update (212,480 of 665,432 KB)...", tapToShow = true),
                cover = null, backdrop = null,
            )
        }
    }

    @Test fun session_loading_game() = paparazzi.snapshot {
        val cover = covers.getValue(games[0].first.appId)
        AppTheme(stillFrame = true) {
            LoadingScreen(
                LoadingState(title = "Aurora Frontier", stage = 2, stages = 4, step = "Steam is starting the game"),
                cover = cover, backdrop = cover,
            )
        }
    }

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

    private fun sessionAchievements(list: List<com.steamoslite.util.SteamFiles.Achievement> = achievements) = paparazzi.snapshot {
        AppTheme(stillFrame = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                SessionAchievements(games[0].first.appId, "Aurora Frontier", read = true, list = list, onClose = {}, image = { _, _ -> null })
            }
        }
    }

    @Test fun session_achievements() = sessionAchievements()

    @Test fun session_achievements_complete() = sessionAchievements(allAchievements)

    @Test fun session_achievements_phone_landscape() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_5.copy(
                screenWidth = 2400,
                screenHeight = 1080,
                density = Density.XXHIGH,
                orientation = ScreenOrientation.LANDSCAPE,
            ),
        )
        sessionAchievements()
    }

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
