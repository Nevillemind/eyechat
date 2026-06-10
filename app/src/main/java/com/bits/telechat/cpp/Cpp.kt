package com.bits.telechat.cpp

object Cpp {
    init {
        System.loadLibrary("lc3")
    }

    external fun decodeLC3(rawPayloads: ByteArray): ByteArray
    external fun rnNoise(pcm: ByteArray): ByteArray
    external fun createRNNoiseState(): Long
    external fun destroyRNNoiseState(statePtr: Long)
}
