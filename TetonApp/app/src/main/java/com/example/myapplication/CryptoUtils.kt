package com.example.myapplication

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object CryptoUtils {

    private const val HKDF_INFO = "teton-provisioning-v1"
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    fun encryptWifiCredentials(
        ssid: String,
        password: String,
        sessionKey: ByteArray,
    ): ByteArray {
        val plaintext = JSONObject()
            .put("ssid", ssid)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        require(sessionKey.size == 32) {
            "AES-256 session key must be 32 bytes"
        }

        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(sessionKey, "AES")
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

        return nonce + cipher.doFinal(plaintext)
    }

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

    fun deriveSessionKey(
        keyPair: KeyPair,
        peerRawPublicKey: ByteArray,
    ): ByteArray {

        require(peerRawPublicKey.size == 32) {
            "X25519 public key must be 32 bytes"
        }

        val peerPublicKey = createPublicKey(peerRawPublicKey)

        val keyAgreement = KeyAgreement.getInstance("X25519")

        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)

        val sharedSecret = keyAgreement.generateSecret()

        return hkdfSha256(
            inputKeyMaterial = sharedSecret,
            info = HKDF_INFO.toByteArray(),
            length = 32,
        )
    }

    private fun createPublicKey(
        rawPublicKey: ByteArray,
    ): java.security.PublicKey {

        val x509Prefix = byteArrayOf(
            0x30.toByte(), 0x2a.toByte(),
            0x30.toByte(), 0x05.toByte(),
            0x06.toByte(), 0x03.toByte(),
            0x2b.toByte(), 0x65.toByte(),
            0x6e.toByte(),
            0x03.toByte(), 0x21.toByte(),
            0x00.toByte()
        )

        val encodedKey = x509Prefix + rawPublicKey

        return KeyFactory
            .getInstance("X25519")
            .generatePublic(
                X509EncodedKeySpec(encodedKey)
            )
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        length: Int,
    ): ByteArray {

        val actualSalt =
            salt ?: ByteArray(32)

        val extractMac =
            Mac.getInstance("HmacSHA256")

        extractMac.init(
            SecretKeySpec(
                actualSalt,
                "HmacSHA256"
            )
        )

        val pseudorandomKey =
            extractMac.doFinal(inputKeyMaterial)

        val expandMac =
            Mac.getInstance("HmacSHA256")

        expandMac.init(
            SecretKeySpec(
                pseudorandomKey,
                "HmacSHA256"
            )
        )

        val result = ByteArray(length)

        var previous = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {

            expandMac.reset()

            expandMac.update(previous)
            expandMac.update(info)
            expandMac.update(counter)

            previous = expandMac.doFinal()

            val bytesToCopy =
                minOf(previous.size, length - offset)

            System.arraycopy(
                previous,
                0,
                result,
                offset,
                bytesToCopy
            )

            offset += bytesToCopy
            counter++
        }

        return result
    }
}
