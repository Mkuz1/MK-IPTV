package com.mk.iptv

import com.mk.iptv.sync.E2e
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class E2eTest {
    @Test fun sealOpenRoundTrip() {
        val k = E2e.newSpaceKey()
        val box = E2e.seal("pässwörd".toByteArray(), k)
        assertEquals("pässwörd", String(E2e.open(box, k)))
        assertThrows(Exception::class.java) { E2e.open(box, E2e.newSpaceKey()) }
    }

    @Test fun wrapForAnotherDevice() {
        val (priv, pub) = E2e.newKeyPair()
        val (otherPriv, _) = E2e.newKeyPair()
        val k = E2e.newSpaceKey()
        val w = E2e.wrap(k, pub)
        assertArrayEquals(k, E2e.unwrap(w.getValue("eph"), w.getValue("box"), priv))
        assertThrows(Exception::class.java) { E2e.unwrap(w.getValue("eph"), w.getValue("box"), otherPriv) }
    }

    @Test fun hkdfMatchesRfc5869Case1() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = E2e.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"))
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.joinToString("") { "%02x".format(it) })
    }

    /** Writes vectors for the iPhone (CryptoKit) interop check when MK_E2E_VECTORS is set. */
    @Test fun writeInteropVectors() {
        val out = System.getenv("MK_E2E_VECTORS") ?: return
        val (priv, pub) = E2e.newKeyPair()
        val k = E2e.newSpaceKey()
        val w = E2e.wrap(k, pub)
        File(out).writeText("""{"priv":"$priv","pub":"$pub","eph":"${w["eph"]}","box":"${w["box"]}","key":"${E2e.b64(k)}","pw":"${E2e.seal("demo-pass-123".toByteArray(), k)}"}""")
    }
}
