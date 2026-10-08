package com.example.myapplication

import java.security.KeyPair
import java.security.KeyPairGenerator

object CryptoUtils {

    fun generateX25519KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("X25519")
        return generator.generateKeyPair()
    }

    fun getRawPublicKey(keyPair: KeyPair): ByteArray {
        val encoded = keyPair.public.encoded

        return encoded.copyOfRange(
            encoded.size - 32,
            encoded.size
        )
    }
}