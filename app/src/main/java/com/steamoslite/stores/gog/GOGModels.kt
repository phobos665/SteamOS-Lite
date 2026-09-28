package com.steamoslite.stores.gog

data class GOGCredentials(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
)
