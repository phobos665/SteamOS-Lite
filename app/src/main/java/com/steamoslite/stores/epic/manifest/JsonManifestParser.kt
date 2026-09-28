package com.steamoslite.stores.epic.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JsonManifestParser {
    companion object {
        fun parse(jsonData: ByteArray): EpicManifest {
            if (jsonData.isEmpty()) {
                throw IllegalArgumentException("Cannot parse empty manifest data")
            }

            val jsonString = String(jsonData, Charsets.UTF_8)
            if (jsonString.isBlank()) {
                throw IllegalArgumentException("Manifest contains only whitespace")
            }

            val json = JSONObject(jsonString)

            val manifest = JsonManifest()
            manifest.version = blobToNum(json.optString("ManifestFileVersion", "013000000000"))
            manifest.storedAs = 0

            manifest.meta = parseManifestMeta(json)
            manifest.chunkDataList = parseChunkDataList(json, manifest.version)
            manifest.fileManifestList = parseFileManifestList(json)
            manifest.customFields = parseCustomFields(json)

            return manifest
        }

        private fun parseManifestMeta(json: JSONObject): ManifestMeta {
            return ManifestMeta(
                featureLevel = blobToNum(json.optString("ManifestFileVersion", "013000000000")),
                isFileData = json.optBoolean("bIsFileData", false),
                appId = blobToNum(json.optString("AppID", "000000000000")),
                appName = json.optString("AppNameString", ""),
                buildVersion = json.optString("BuildVersionString", ""),
                launchExe = json.optString("LaunchExeString", ""),
                launchCommand = json.optString("LaunchCommand", ""),
                prereqIds = jsonArrayToStringList(json.optJSONArray("PrereqIds")),
                prereqName = json.optString("PrereqName", ""),
                prereqPath = json.optString("PrereqPath", ""),
                prereqArgs = json.optString("PrereqArgs", "")
            )
        }

        private fun parseChunkDataList(json: JSONObject, manifestVersion: Int): ChunkDataList {
            val cdl = ChunkDataList(manifestVersion = manifestVersion)

            val fileSizeList = json.optJSONObject("ChunkFilesizeList") ?: JSONObject()
            val hashList = json.optJSONObject("ChunkHashList") ?: JSONObject()
            val shaList = json.optJSONObject("ChunkShaList") ?: JSONObject()
            val groupList = json.optJSONObject("DataGroupList") ?: JSONObject()

            val guids = fileSizeList.keys().asSequence().toList()
            cdl.count = guids.size

            for (guidStr in guids) {
                val chunk = ChunkInfo(manifestVersion = manifestVersion)
                chunk.guid = guidFromJson(guidStr)
                chunk.fileSize = blobToLong(fileSizeList.optString(guidStr, "0"))
                chunk.hash = blobToULong(hashList.optString(guidStr, "0"))
                chunk.shaHash = hexStringToByteArray(shaList.optString(guidStr, ""))
                chunk.groupNum = blobToNum(groupList.optString(guidStr, "0"))
                chunk.windowSize = 1024 * 1024
                chunk.useHashPrefixForV3 = false

                cdl.elements.add(chunk)
            }

            return cdl
        }

        private fun parseFileManifestList(json: JSONObject): FileManifestList {
            val fml = FileManifestList()
            val fileList = json.optJSONArray("FileManifestList") ?: JSONArray()
            fml.count = fileList.length()

            for (i in 0 until fileList.length()) {
                val fileJson = fileList.getJSONObject(i)
                val fm = FileManifest()

                fm.filename = fileJson.optString("Filename", "")

                fm.hash = blobToByteArray(fileJson.optString("FileHash", "0"), 20)

                var flags = 0
                if (fileJson.optBoolean("bIsReadOnly", false)) flags = flags or 0x1
                if (fileJson.optBoolean("bIsCompressed", false)) flags = flags or 0x2
                if (fileJson.optBoolean("bIsUnixExecutable", false)) flags = flags or 0x4
                fm.flags = flags

                fm.installTags = jsonArrayToStringList(fileJson.optJSONArray("InstallTags"))

                val chunkParts = fileJson.optJSONArray("FileChunkParts") ?: JSONArray()
                var fileOffset = 0L

                for (j in 0 until chunkParts.length()) {
                    val partJson = chunkParts.getJSONObject(j)

                    val part = ChunkPart(
                        guid = guidFromJson(partJson.getString("Guid")),
                        offset = blobToNum(partJson.getString("Offset")),
                        size = blobToNum(partJson.getString("Size")),
                        fileOffset = fileOffset
                    )

                    fm.chunkParts.add(part)
                    fileOffset += part.size.toLong()
                }

                fm.fileSize = fileOffset
                fml.elements.add(fm)
            }

            return fml
        }

        private fun parseCustomFields(json: JSONObject): CustomFields {
            val cf = CustomFields()
            val customFieldsJson = json.optJSONObject("CustomFields") ?: JSONObject()

            for (key in customFieldsJson.keys()) {
                cf[key] = customFieldsJson.optString(key, "")
            }

            return cf
        }

        private fun blobToNum(blobStr: String): Int {
            if (blobStr.isEmpty()) return 0

            var num = 0
            var shift = 0

            var i = 0
            while (i < blobStr.length) {
                val byteStr = blobStr.substring(i, minOf(i + 3, blobStr.length))
                val byteVal = byteStr.toIntOrNull() ?: 0
                num = num or (byteVal shl shift)
                shift += 8
                i += 3
            }

            return num
        }

        private fun blobToLong(blobStr: String): Long {
            if (blobStr.isEmpty()) return 0L

            var num = 0L
            var shift = 0

            var i = 0
            while (i < blobStr.length) {
                val byteStr = blobStr.substring(i, minOf(i + 3, blobStr.length))
                val byteVal = byteStr.toLongOrNull() ?: 0L
                num = num or (byteVal shl shift)
                shift += 8
                i += 3
            }

            return num
        }

        private fun blobToULong(blobStr: String): ULong {
            if (blobStr.isEmpty()) return 0u

            var num = 0uL
            var shift = 0

            var i = 0
            while (i < blobStr.length) {
                val byteStr = blobStr.substring(i, minOf(i + 3, blobStr.length))
                val byteVal = byteStr.toULongOrNull() ?: 0uL
                num = num or (byteVal shl shift)
                shift += 8
                i += 3
            }

            return num
        }

        private fun blobToByteArray(blobStr: String, size: Int): ByteArray {
            if (blobStr.isEmpty()) return ByteArray(size)

            val result = ByteArray(size)
            var i = 0
            var byteIndex = 0

            while (i < blobStr.length && byteIndex < size) {
                val byteStr = blobStr.substring(i, minOf(i + 3, blobStr.length))
                val byteVal = byteStr.toIntOrNull() ?: 0
                result[byteIndex] = byteVal.toByte()
                byteIndex++
                i += 3
            }

            return result
        }

        private fun guidFromJson(guidHex: String): IntArray {
            val cleanHex = guidHex.replace("-", "")
            val bytes = hexStringToByteArray(cleanHex)

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return IntArray(4) { buffer.int }
        }

        private fun hexStringToByteArray(hex: String): ByteArray {
            if (hex.isEmpty()) return ByteArray(0)

            val cleanHex = hex.replace("-", "").replace(" ", "")
            val len = cleanHex.length
            val data = ByteArray(len / 2)

            for (i in data.indices) {
                val index = i * 2
                val byte = cleanHex.substring(index, index + 2).toInt(16)
                data[i] = byte.toByte()
            }

            return data
        }

        private fun jsonArrayToStringList(jsonArray: JSONArray?): List<String> {
            if (jsonArray == null) return emptyList()

            return List(jsonArray.length()) { i ->
                jsonArray.optString(i, "")
            }
        }
    }
}
