package com.fiwly.rgb

interface Ds4Transport {
    val name: String
    suspend fun connect(): Result<Unit>
    suspend fun isConnected(): Boolean
    suspend fun setLightbar(color: Ds4Color): Result<Unit>
    fun close()
}
