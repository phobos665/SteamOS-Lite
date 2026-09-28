package com.steamoslite.stores.epic

data class EpicCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val displayName: String,
    val expiresAt: Long = 0,
)

data class EpicGameToken(
    val authCode: String,
    val accountId: String,
    val displayName: String,
    val ownershipToken: String? = null,
)
