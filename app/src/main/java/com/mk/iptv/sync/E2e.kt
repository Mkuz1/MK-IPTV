package com.mk.iptv.sync

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for synced secrets (playlist passwords). Same format as the iPhone app (CryptoKit):
 *  - every device has a P-256 key pair; the public key is published as base64 SubjectPublicKeyInfo DER
 *  - a library has one random 32-byte AES key ("space key"), never stored in Firebase in the clear
 *  - the space key is wrapped for each device: ephemeral P-256 ECDH -> HKDF-SHA256 -> AES-256-GCM
 *  - secrets are sealed with AES-256-GCM; boxes are base64(nonce(12) || ciphertext || tag(16))
 */
object E2e {
    private const val WRAP_SALT = "MKIPTV-keywrap-v1"
    private const val WRAP_INFO = "space-key"
    private val rnd = SecureRandom()

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun newKeyPair(): Pair<String, String> {   // (private PKCS8 b64, public SPKI b64)
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }
        val kp = g.generateKeyPair()
        return b64(kp.private.encoded) to b64(kp.public.encoded)
    }

    private fun priv(b64: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(b64)))
    private fun pub(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(b64)))

    fun newSpaceKey(): ByteArray = ByteArray(32).also(rnd::nextBytes)

    /** HKDF-SHA256 (RFC 5869) with a single 32-byte output block, as CryptoKit's hkdfDerivedSymmetricKey. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info); mac.update(1)
        return mac.doFinal()
    }

    fun seal(plain: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return b64(nonce + c.doFinal(plain))
    }

    fun open(box: String, key: ByteArray): ByteArray {
        val all = unb64(box)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }

    private fun shared(privB64: String, pubB64: String): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv(privB64)); doPhase(pub(pubB64), true); generateSecret() }

    /** Wraps [spaceKey] for the device whose public key is [recipientPub]. Returns the Firestore fields. */
    fun wrap(spaceKey: ByteArray, recipientPub: String): Map<String, String> {
        val (ephPriv, ephPub) = newKeyPair()
        val k = hkdf(shared(ephPriv, recipientPub), WRAP_SALT.toByteArray(), WRAP_INFO.toByteArray())
        return mapOf("eph" to ephPub, "box" to seal(spaceKey, k))
    }

    fun unwrap(eph: String, box: String, myPriv: String): ByteArray {
        val k = hkdf(shared(myPriv, eph), WRAP_SALT.toByteArray(), WRAP_INFO.toByteArray())
        return open(box, k)
    }
}
