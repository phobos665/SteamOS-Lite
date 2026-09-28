package com.steamoslite.stores.gog.api

import org.json.JSONObject

data class BuildsResponse(
    val totalCount: Int,
    val count: Int,
    val items: List<GOGBuild>
) {
    companion object {
        fun fromJson(json: JSONObject): BuildsResponse {
            val itemsArray = json.optJSONArray("items")
            val items = mutableListOf<GOGBuild>()

            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    items.add(GOGBuild.fromJson(itemsArray.getJSONObject(i)))
                }
            }

            return BuildsResponse(
                totalCount = json.optInt("total_count", 0),
                count = json.optInt("count", 0),
                items = items
            )
        }
    }
}

data class DependencyRepository(
    val repositoryManifest: String,
    val generation: Int,
    val buildId: String
)   { companion object {
        fun fromJson(json: JSONObject): DependencyRepository {
            return DependencyRepository(
                repositoryManifest = json.optString("repository_manifest", ""),
                buildId = json.optString("build_id", ""),
                generation = json.optInt("generation", 2),
            )
        }
    }
}

data class GOGBuild(
    val buildId: String,
    val productId: String,
    val platform: String,
    val generation: Int,
    val versionName: String,
    val branch: String?,
    val link: String,
    val legacyBuildId: String?
) {
    companion object {
        fun fromJson(json: JSONObject): GOGBuild {
            return GOGBuild(
                buildId = json.optString("build_id", ""),
                productId = json.optString("product_id", ""),
                platform = json.optString("os", "windows"),
                generation = json.optInt("generation", 2),
                versionName = json.optString("version_name", ""),
                branch = if (json.has("branch") && !json.isNull("branch")) json.getString("branch") else null,
                link = json.optString("link", ""),
                legacyBuildId = if (json.has("legacy_build_id") && !json.isNull("legacy_build_id")) json.getString("legacy_build_id") else null
            )
        }
    }
}

data class Executable(
    val arguments: String?,
    val path: String
)

data class DependencyDepot(
    val compressedSize: Long,
    val dependencyId: String,
    val executable: Executable?,
    val isInternal: Boolean,
    val languages: List<String>,
    val manifest: String,
    val osBitness: List<String>?,
    val readableName: String,
    val signature: String,
    val size: Long,
)

data class GOGDependencyManifestMeta(
    val depots: List<DependencyDepot>,
) {
    companion object {
        fun fromJson(json: JSONObject): GOGDependencyManifestMeta {
            val depotsArray = json.optJSONArray("depots")
            val depots = mutableListOf<DependencyDepot>()

            if(depotsArray != null) {
                for (i in 0 until depotsArray.length()) {
                    val depotObj = depotsArray.getJSONObject(i)

                    val languagesArray = depotObj.optJSONArray("languages")
                    val languages = mutableListOf<String>()
                    if (languagesArray != null) {
                        for (j in 0 until languagesArray.length()) {
                            languages.add(languagesArray.getString(j))
                        }
                    }

                    val bitnessArray = depotObj.optJSONArray("osBitness")
                    val osBitness = if (bitnessArray != null) {
                        val list = mutableListOf<String>()
                        for (j in 0 until bitnessArray.length()) {
                            list.add(bitnessArray.getString(j))
                        }
                        list
                    } else null

                    val executableObj = depotObj.optJSONObject("executable")
                    val executable = if (executableObj != null) {
                        Executable(
                            arguments = if (executableObj.has("arguments") && !executableObj.isNull("arguments"))
                                executableObj.getString("arguments") else null,
                            path = executableObj.optString("path", "")
                        )
                    } else null

                    val depot = DependencyDepot (
                        compressedSize = depotObj.optLong("compressedSize", 0),
                        dependencyId = depotObj.optString("dependencyId", ""),
                        executable = executable,
                        languages = languages,
                        osBitness = osBitness,
                        isInternal = depotObj.optBoolean("internal", false),
                        manifest = depotObj.optString("manifest", ""),
                        readableName = depotObj.optString("readableName", ""),
                        signature = depotObj.optString("signature", ""),
                        size = depotObj.optLong("size", 0),
                    )
                    depots.add(depot)
                }
            }

            return GOGDependencyManifestMeta(depots = depots)
        }
    }
}

data class GOGManifestMeta(
    val baseProductId: String,
    val installDirectory: String,
    val depots: List<Depot>,
    val dependencies: List<String>,
    val products: List<Product>,
    val productTimestamp: String? = null,
    val scriptInterpreter: Boolean = false,
    val supportCommands: List<SupportCommand> = emptyList(),
) {
    companion object {
        fun fromJson(json: JSONObject): GOGManifestMeta {
            val depotsArray = json.optJSONArray("depots")
            val depots = mutableListOf<Depot>()

            if (depotsArray != null) {
                for (i in 0 until depotsArray.length()) {
                    depots.add(Depot.fromJson(depotsArray.getJSONObject(i)))
                }
            }

            val dependenciesArray = json.optJSONArray("dependencies")
            val dependencies = mutableListOf<String>()

            if (dependenciesArray != null) {
                for (i in 0 until dependenciesArray.length()) {
                    dependencies.add(dependenciesArray.getString(i))
                }
            }

            val productsArray = json.optJSONArray("products")
            val products = mutableListOf<Product>()

            if (productsArray != null) {
                for (i in 0 until productsArray.length()) {
                    products.add(Product.fromJson(productsArray.getJSONObject(i)))
                }
            }

            return GOGManifestMeta(
                baseProductId = json.optString("baseProductId", ""),
                installDirectory = json.optString("installDirectory", ""),
                depots = depots,
                dependencies = dependencies,
                products = products,
                productTimestamp = null,
                scriptInterpreter = json.optBoolean("scriptInterpreter", false),
            )
        }
    }
}

data class V1DepotFile(
    val path: String,
    val size: Long,
    val hash: String,
    val url: String?,
    val offset: Long?,
    val isSupport: Boolean = false
)

private val GOG_LANGUAGE_DEPRECATED: Map<String, Set<String>> = mapOf(
    "en-US" to setOf("en"),
    "en-GB" to setOf("en"),
)

data class Depot(
    val productId: String,
    val languages: List<String>,
    val manifest: String,
    val compressedSize: Long,
    val size: Long,
    val osBitness: List<String>?
) {
    companion object {
        fun fromJson(json: JSONObject): Depot {
            val languagesArray = json.optJSONArray("languages")
            val languages = mutableListOf<String>()

            if (languagesArray != null) {
                for (i in 0 until languagesArray.length()) {
                    languages.add(languagesArray.getString(i))
                }
            }

            val bitnessArray = json.optJSONArray("osBitness")
            val osBitness = if (bitnessArray != null) {
                val list = mutableListOf<String>()
                for (i in 0 until bitnessArray.length()) {
                    list.add(bitnessArray.getString(i))
                }
                list
            } else null

            return Depot(
                productId = json.optString("productId", ""),
                languages = languages,
                manifest = json.optString("manifest", ""),
                compressedSize = json.optLong("compressedSize", 0),
                size = json.optLong("size", 0),
                osBitness = osBitness
            )
        }
    }

    fun matchesLanguage(targetLanguage: String): Boolean =
        languages.any { it.equals(targetLanguage, ignoreCase = true) }
}

data class SupportCommand(
    val executable: String,
    val gameId: String,
    val argument: String = "",
    val languages: List<String> = emptyList(),
)

data class Product(
    val productId: String,
    val name: String,
    val temp_executable: String? = null,
    val temp_arguments: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): Product {
            return Product(
                productId = json.optString("productId", ""),
                name = json.optString("name", ""),
                temp_executable = json.optString("temp_executable", "").takeIf { it.isNotEmpty() },
                temp_arguments = json.optString("temp_arguments", "").takeIf { it.isNotEmpty() },
            )
        }
    }
}

data class DepotManifest(
    val files: List<DepotFile>,
    val directories: List<DepotDirectory>,
    val links: List<DepotLink>
) {
    companion object {
        fun fromJson(json: JSONObject): DepotManifest {
            val depotObj = json.optJSONObject("depot") ?: json
            val itemsArray = depotObj.optJSONArray("items")

            val files = mutableListOf<DepotFile>()
            val directories = mutableListOf<DepotDirectory>()
            val links = mutableListOf<DepotLink>()

            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.getJSONObject(i)
                    when (item.optString("type", "")) {
                        "DepotFile" -> files.add(DepotFile.fromJson(item))
                        "DepotDirectory" -> directories.add(DepotDirectory.fromJson(item))
                        "DepotLink" -> links.add(DepotLink.fromJson(item))
                    }
                }
            }

            return DepotManifest(
                files = files,
                directories = directories,
                links = links
            )
        }
    }
}

data class DepotFile(
    val path: String,
    val chunks: List<FileChunk>,
    val md5: String?,
    val sha256: String?,
    val flags: List<String>,
    val productId: String?
) {
    companion object {
        fun fromJson(json: JSONObject): DepotFile {
            val chunksArray = json.optJSONArray("chunks")
            val chunks = mutableListOf<FileChunk>()

            if (chunksArray != null) {
                for (i in 0 until chunksArray.length()) {
                    chunks.add(FileChunk.fromJson(chunksArray.getJSONObject(i)))
                }
            }

            val flagsArray = json.optJSONArray("flags")
            val flags = mutableListOf<String>()

            if (flagsArray != null) {
                for (i in 0 until flagsArray.length()) {
                    flags.add(flagsArray.getString(i))
                }
            }

            return DepotFile(
                path = json.optString("path", "").replace("\\", "/").removePrefix("/"),
                chunks = chunks,
                md5 = if (json.has("md5") && !json.isNull("md5")) json.getString("md5") else null,
                sha256 = if (json.has("sha256") && !json.isNull("sha256")) json.getString("sha256") else null,
                flags = flags,
                productId = if (json.has("productId") && !json.isNull("productId")) json.getString("productId") else null
            )
        }
    }

    fun isSupportFile(): Boolean = flags.contains("support")
}

data class FileChunk(
    val compressedMd5: String,
    val md5: String,
    val size: Long,
    val compressedSize: Long?
) {
    companion object {
        fun fromJson(json: JSONObject): FileChunk {
            return FileChunk(
                compressedMd5 = json.optString("compressedMd5", ""),
                md5 = json.optString("md5", ""),
                size = json.optLong("size", 0),
                compressedSize = if (json.has("compressedSize") && !json.isNull("compressedSize")) {
                    json.optLong("compressedSize", 0)
                } else {
                    null
                }
            )
        }
    }
}

data class DepotDirectory(
    val path: String
) {
    companion object {
        fun fromJson(json: JSONObject): DepotDirectory {
            return DepotDirectory(
                path = json.optString("path", "").replace("\\", "/").removeSuffix("/")
            )
        }
    }
}

data class DepotLink(
    val path: String,
    val target: String
) {
    companion object {
        fun fromJson(json: JSONObject): DepotLink {
            return DepotLink(
                path = json.optString("path", ""),
                target = json.optString("target", "")
            )
        }
    }
}

data class SecureLinksResponse(
    val urls: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): SecureLinksResponse {
            val urlsArray = json.optJSONArray("urls")
            val urls = mutableListOf<String>()

            if (urlsArray != null) {
                for (i in 0 until urlsArray.length()) {
                    val urlObj = urlsArray.optJSONObject(i)
                    if (urlObj != null) {
                        val urlFormat = urlObj.optString("url_format", "")
                        val paramsObj = urlObj.optJSONObject("parameters")

                        if (urlFormat.isNotEmpty() && paramsObj != null) {
                            var constructedUrl = urlFormat
                            val keys = paramsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = paramsObj.get(key).toString()
                                constructedUrl = constructedUrl.replace("{$key}", value)
                            }

                            constructedUrl = constructedUrl.replace("\\/", "/")

                            if (constructedUrl.isNotEmpty()) {
                                urls.add(constructedUrl)
                            }
                        }
                    }
                }
            }

            return SecureLinksResponse(urls = urls)
        }
    }
}
