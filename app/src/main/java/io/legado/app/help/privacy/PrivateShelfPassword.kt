package io.legado.app.help.privacy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal val PRIVATE_SHELF_PASSWORD_REGEX = Regex("^[A-Za-z0-9]{3,}$")

internal const val PRIVATE_SHELF_HASH_PREFIX = "pbkdf2_sha256"
internal const val PRIVATE_SHELF_HASH_ITERATIONS = 120_000
private const val PRIVATE_SHELF_SALT_BYTES = 16
private const val PRIVATE_SHELF_HASH_BITS = 256

internal enum class PrivateShelfVerifyResult {
    NOT_SET,
    MATCH,
    MISMATCH,
    CORRUPT,
}

internal fun isValidPrivateShelfPassword(raw: String): Boolean =
    raw.matches(PRIVATE_SHELF_PASSWORD_REGEX)

internal fun newPrivateShelfSalt(): ByteArray {
    val salt = ByteArray(PRIVATE_SHELF_SALT_BYTES)
    SecureRandom().nextBytes(salt)
    return salt
}

internal fun encodePrivateShelfPassword(
    raw: CharSequence,
    salt: ByteArray = newPrivateShelfSalt(),
    iterations: Int = PRIVATE_SHELF_HASH_ITERATIONS,
): String {
    require(iterations > 0)
    val hash = derivePrivateShelfHash(raw, salt, iterations)
    return listOf(
        PRIVATE_SHELF_HASH_PREFIX,
        iterations.toString(),
        Base64.getEncoder().encodeToString(salt),
        Base64.getEncoder().encodeToString(hash),
    ).joinToString("$")
}

internal fun verifyPrivateShelfPassword(
    raw: CharSequence,
    stored: String?,
): PrivateShelfVerifyResult {
    if (stored.isNullOrBlank()) return PrivateShelfVerifyResult.NOT_SET
    val parts = stored.split("$")
    if (parts.size != 4 || parts[0] != PRIVATE_SHELF_HASH_PREFIX) {
        return PrivateShelfVerifyResult.CORRUPT
    }
    val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 }
        ?: return PrivateShelfVerifyResult.CORRUPT
    val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()
        ?: return PrivateShelfVerifyResult.CORRUPT
    val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
        ?: return PrivateShelfVerifyResult.CORRUPT
    if (salt.isEmpty() || expected.isEmpty()) return PrivateShelfVerifyResult.CORRUPT
    val actual = derivePrivateShelfHash(raw, salt, iterations)
    return if (constantTimeEquals(expected, actual)) {
        PrivateShelfVerifyResult.MATCH
    } else {
        PrivateShelfVerifyResult.MISMATCH
    }
}

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
    MessageDigest.isEqual(a, b)

private fun derivePrivateShelfHash(
    raw: CharSequence,
    salt: ByteArray,
    iterations: Int,
): ByteArray {
    val spec = PBEKeySpec(raw.toString().toCharArray(), salt, iterations, PRIVATE_SHELF_HASH_BITS)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    } finally {
        spec.clearPassword()
    }
}
