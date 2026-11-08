package com.mk.iptv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts playlist passwords with an AES-GCM key that never leaves the Android Keystore. */
object Crypto {
    private const val ALIAS = "mk_iptv_credentials"
    private const val PREFIX = "k1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        "p:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String = try {
        when {
            enc.startsWith(PREFIX) -> {
                val raw = Base64.decode(enc.removePrefix(PREFIX), Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(c.doFinal(raw, 12, raw.size - 12))
            }
            enc.startsWith("p:") -> String(Base64.decode(enc.removePrefix("p:"), Base64.NO_WRAP))
            else -> enc
        }
    } catch (e: Exception) {
        ""
    }
}
