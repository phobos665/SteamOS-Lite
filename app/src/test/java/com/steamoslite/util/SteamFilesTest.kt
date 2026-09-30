package com.steamoslite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class SteamFilesTest {
    /** Binary KeyValues writer for the tests: String, Int, Long or nested Map values. */
    private class Kv(val keys: MutableList<String>? = null) {
        val out = ByteArrayOutputStream()
        private fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        private fun le(n: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
        private fun name(k: String) {
            if (keys != null) {
                if (k !in keys) keys += k
                out.write(le(keys.indexOf(k)))
            } else {
                out.write(k.toByteArray()); out.write(0)
            }
        }
        fun write(map: Map<String, Any>): Kv {
            for ((k, v) in map) {
                when (v) {
                    is Map<*, *> -> { out.write(0); name(k); @Suppress("UNCHECKED_CAST") write(v as Map<String, Any>) }
                    is String -> { out.write(1); name(k); out.write(v.toByteArray()); out.write(0) }
                    is Int -> { out.write(2); name(k); out.write(le(v)) }
                    is Long -> { out.write(7); name(k); out.write(le(v)) }
                }
            }
            out.write(8)
            return this
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    @Test
    fun textVdfWithCommentsEscapesAndNesting() {
        val kv = KeyValues.parseText(
            """
            // comment
            "UserLocalConfigStore"
            {
                "Software" { "valve" { "Steam" { "apps" {
                    "220" { "LastPlayed" "1700000000" "Playtime" "125" }
                } } } }
                "quote" "say \"hi\""
            }
            """.trimIndent(),
        )
        assertEquals("125", KeyValues.string(kv, "userlocalconfigstore", "software", "Valve", "steam", "Apps", "220", "Playtime"))
        assertEquals("say \"hi\"", KeyValues.string(kv, "UserLocalConfigStore", "quote"))
    }

    @Test
    fun playtimeAndAccount() {
        val dir = Files.createTempDirectory("steam").toFile()
        try {
            val local = File(dir, "localconfig.vdf").apply {
                writeText(""""UserLocalConfigStore" { "Software" { "Valve" { "Steam" { "apps" { "220" { "LastPlayed" "1700000000" "Playtime" "125" } } } } } }""")
            }
            assertEquals(SteamFiles.Playtime(125, 1700000000), SteamFiles.playtime(local, "220"))
            assertNull(SteamFiles.playtime(local, "440"))
            val users = File(dir, "loginusers.vdf").apply {
                writeText(""""users" { "76561197960265829" { "AccountName" "old" "MostRecent" "0" "Timestamp" "5" }
                    "76561197960265828" { "AccountName" "me" "PersonaName" "Me" "MostRecent" "1" "Timestamp" "1" } }""")
            }
            val account = SteamFiles.account(users)!!
            assertEquals(100L, account.accountId)
            assertEquals("Me", account.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appInfoFormat29FindsTheEntryAndSkipsOthers() {
        val keys = mutableListOf<String>()
        fun entry(appId: Int, kv: ByteArray): ByteArray {
            val body = ByteBuffer.allocate(4 + 4 + 8 + 20 + 4 + 20 + kv.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2).putInt(0).putLong(0).put(ByteArray(20)).putInt(1).put(ByteArray(20)).put(kv).array()
            return ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(appId).putInt(body.size).put(body).array()
        }
        val other = Kv(keys).write(mapOf("appinfo" to mapOf("common" to mapOf("name" to "Other")))).bytes()
        val game = Kv(keys).write(
            mapOf(
                "appinfo" to mapOf(
                    "appid" to 220,
                    "common" to mapOf(
                        "name" to "Half-Life 2",
                        "type" to "Game",
                        "steam_release_date" to "1100563200",
                        "metacritic_score" to "96",
                        "controller_support" to "full",
                        "steam_deck_compatibility" to mapOf("category" to "3"),
                        "associations" to mapOf(
                            "0" to mapOf("type" to "developer", "name" to "Valve"),
                            "1" to mapOf("type" to "publisher", "name" to "Valve Pub"),
                        ),
                    ),
                ),
            ),
        ).bytes()
        val entries = entry(10, other) + entry(220, game) + ByteArray(4)
        val table = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(keys.size).array())
            keys.forEach { write(it.toByteArray()); write(0) }
        }.toByteArray()
        val header = 4 + 4 + 8
        val file = ByteBuffer.allocate(header + entries.size + table.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x07564429).putInt(1).putLong((header + entries.size).toLong()).put(entries).put(table).array()
        val f = Files.createTempFile("appinfo", ".vdf").toFile()
        try {
            f.writeBytes(file)
            val info = SteamFiles.appInfo(f, 220)!!
            assertEquals("Half-Life 2", info.name)
            assertEquals("Valve", info.developer)
            assertEquals("Valve Pub", info.publisher)
            assertEquals(1100563200L, info.releaseDate)
            assertEquals(96, info.metacritic)
            assertEquals(3, info.deckCategory)
            assertEquals("full", info.controllerSupport)
            assertNull(SteamFiles.appInfo(f, 999))
            // Every app in one pass, and only the ones asked for when given.
            val all = SteamFiles.appKinds(f, null)
            assertEquals(SteamFiles.AppKind("Half-Life 2", "game"), all[220L])
            assertEquals("Other", all[10L]?.name)
            assertEquals(setOf(220L), SteamFiles.appKinds(f, setOf(220L)).keys)
        } finally {
            f.delete()
        }
    }

    @Test
    fun achievementsMergeSchemaAndProgress() {
        val schema = Kv().write(
            mapOf(
                "220" to mapOf(
                    "gamename" to "Half-Life 2",
                    "stats" to mapOf(
                        "1" to mapOf(
                            "type" to "4",
                            "bits" to mapOf(
                                "0" to mapOf("name" to "ACH_A", "bit" to 0, "display" to mapOf(
                                    "name" to mapOf("english" to "First", "token" to "NEW_ACHIEVEMENT_1_0"),
                                    "desc" to mapOf("english" to "Do the first thing"),
                                    "hidden" to "0", "icon" to "a.jpg", "icon_gray" to "a_gray.jpg")),
                                "1" to mapOf("name" to "ACH_B", "bit" to 1, "display" to mapOf(
                                    "name" to "Second", "desc" to "Secret", "hidden" to "1")),
                            ),
                        ),
                    ),
                ),
            ),
        ).bytes()
        val stats = Kv().write(
            mapOf("cache" to mapOf("crc" to 7, "1" to mapOf("data" to 0b01, "AchievementTimes" to mapOf("0" to 1700000000)))),
        ).bytes()

        val list = SteamFiles.achievements(schema, stats)!!
        assertEquals(2, list.size)
        val a = list.first { it.id == "ACH_A" }
        assertEquals("First", a.name)
        assertEquals("Do the first thing", a.description)
        assertTrue(a.unlocked)
        assertEquals(1700000000L, a.unlockedAt)
        assertEquals("a.jpg", a.icon)
        val b = list.first { it.id == "ACH_B" }
        assertFalse(b.unlocked)
        assertTrue(b.hidden)
        assertEquals("Second", b.name)

        // No progress file yet: everything locked.
        assertTrue(SteamFiles.achievements(schema, null)!!.none { it.unlocked })
    }
}
