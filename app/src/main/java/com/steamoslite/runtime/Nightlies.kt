package com.steamoslite.runtime

import com.steamoslite.util.Downloader
import org.json.JSONObject

/**
 * The "-Linux" component builds published in The412Banner/Nightlies: FEX, DXVK (including ARM64EC
 * builds, which run natively rather than under FEX) and VKD3D-Proton, repacked for Proton on a
 * glibc ARM64 host. Each release carries its builds as .wcp packages with a GitHub sha256 digest.
 */
object Nightlies {
    private const val REPO = "The412Banner/Nightlies"
    private val RELEASES = mapOf(
        FexCore to listOf("FexCore-Linux"),
        Dxvk to listOf("Dxvk-arm64ec-Linux", "Dxvk-Linux", "Dxvk-gplasync-Linux"),
        Vkd3d to listOf("Vkd3d-proton-Linux"),
    )

    data class Item(val file: String, val release: String, val url: String, val size: Long, val sha256: String?) {
        val arm64ec get() = "arm64ec" in release.lowercase() || "arm64ec" in file.lowercase()
    }

    /** The newest [perRelease] builds of each release for [store], or empty when GitHub cannot be reached. */
    fun catalog(store: ComponentStore, perRelease: Int = 3): List<Item> =
        RELEASES[store].orEmpty().flatMap { tag ->
            val body = Downloader.downloadString("https://api.github.com/repos/$REPO/releases/tags/$tag") ?: return@flatMap emptyList()
            runCatching {
                val assets = JSONObject(body).getJSONArray("assets")
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".wcp") }
                    .sortedByDescending { it.optString("updated_at") }
                    .take(perRelease)
                    .map {
                        Item(
                            it.optString("name"), tag, it.optString("browser_download_url"), it.optLong("size"),
                            it.optString("digest").takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:"),
                        )
                    }
            }.getOrDefault(emptyList())
        }
}
