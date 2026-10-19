package dev.owndemand.app.data.settings

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip tests for the token encryption blob format, using a plain JVM
 * AES key (the production class uses the same AES/GCM transformation with a
 * Keystore-backed key).
 */
class TokenStoreTest {

    private val store = TokenStore {
        KeyGenerator.getInstance("AES").also { it.init(256) }.generateKey()
    }

    @Test
    fun `token survives encrypt-decrypt round trip`() {
        val token = "abc123XYZ_-token-9f8e7d6c5b4a"
        assertEquals(token, store.decrypt(store.encrypt(token)))
    }

    @Test
    fun `unicode token survives round trip`() {
        val token = "tökén-éè-日本語-🎬"
        assertEquals(token, store.decrypt(store.encrypt(token)))
    }

    @Test
    fun `two encryptions of the same token differ (random IV)`() {
        val token = "same-token"
        val a = store.encrypt(token)
        val b = store.encrypt(token)
        org.junit.Assert.assertFalse(a.contentEquals(b))
        assertEquals(token, store.decrypt(a))
        assertEquals(token, store.decrypt(b))
    }

    @Test
    fun `tampered blob decrypts to null, not garbage`() {
        val blob = store.encrypt("secret-token").copyOf()
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertNull(store.decrypt(blob))
    }

    @Test
    fun `short blob decrypts to null`() {
        assertNull(store.decrypt(ByteArray(4)))
    }
}
