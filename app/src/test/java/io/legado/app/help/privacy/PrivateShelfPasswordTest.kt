package io.legado.app.help.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateShelfPasswordTest {

    @Test
    fun `password policy accepts three or more letters or digits`() {
        assertTrue(isValidPrivateShelfPassword("abc"))
        assertTrue(isValidPrivateShelfPassword("123"))
        assertTrue(isValidPrivateShelfPassword("a1c2"))
        assertFalse(isValidPrivateShelfPassword("ab"))
        assertFalse(isValidPrivateShelfPassword(""))
        assertFalse(isValidPrivateShelfPassword("ab c"))
        assertFalse(isValidPrivateShelfPassword("中文abc"))
        assertFalse(isValidPrivateShelfPassword("abc!"))
    }

    @Test
    fun `encoded password verifies and rejects a wrong password`() {
        val salt = ByteArray(16) { it.toByte() }
        val stored = encodePrivateShelfPassword("abc123", salt, iterations = 1_000)

        assertEquals(
            PrivateShelfVerifyResult.MATCH,
            verifyPrivateShelfPassword("abc123", stored),
        )
        assertEquals(
            PrivateShelfVerifyResult.MISMATCH,
            verifyPrivateShelfPassword("abc124", stored),
        )
    }

    @Test
    fun `every encode uses a fresh salt`() {
        val first = encodePrivateShelfPassword("abc123", iterations = 1_000)
        val second = encodePrivateShelfPassword("abc123", iterations = 1_000)

        assertNotEquals(first, second)
        assertEquals(PrivateShelfVerifyResult.MATCH, verifyPrivateShelfPassword("abc123", first))
        assertEquals(PrivateShelfVerifyResult.MATCH, verifyPrivateShelfPassword("abc123", second))
    }

    @Test
    fun `missing and malformed storage are reported separately`() {
        assertEquals(
            PrivateShelfVerifyResult.NOT_SET,
            verifyPrivateShelfPassword("abc", null),
        )
        assertEquals(
            PrivateShelfVerifyResult.NOT_SET,
            verifyPrivateShelfPassword("abc", ""),
        )
        assertEquals(
            PrivateShelfVerifyResult.CORRUPT,
            verifyPrivateShelfPassword("abc", "plain-text"),
        )
        assertEquals(
            PrivateShelfVerifyResult.CORRUPT,
            verifyPrivateShelfPassword("abc", "pbkdf2_sha256\$0\$AA==\$AA=="),
        )
        assertEquals(
            PrivateShelfVerifyResult.CORRUPT,
            verifyPrivateShelfPassword("abc", "pbkdf2_sha256\$1000\$***\$AA=="),
        )
    }

    @Test
    fun `constant time compare rejects different lengths`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
    }
}
