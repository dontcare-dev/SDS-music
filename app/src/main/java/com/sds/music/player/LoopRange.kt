package com.sds.music.player

data class LoopRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0); require(endMs > startMs) }
    fun contains(positionMs: Long) = positionMs in startMs until endMs
}
