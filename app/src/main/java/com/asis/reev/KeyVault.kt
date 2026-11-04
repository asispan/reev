package com.asis.reev

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the user's cloud API keys at rest with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where the phone supports it). The wrapping key never leaves the Keystore, so a copy of
 * the app's files (backup, adb, file extraction) yields only ciphertext.
 */
object KeyVault {
    private const val ALIAS = "reev-api-keys"
    private const val GCM_TAG_BITS = 128

    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private const val PREFIX = "v1:"

    /**
     * Encrypts [plain] for the preference named [field]. Output: "v1:" + Base64(IV ‖ ciphertext+tag).
     * [field] is bound in as associated data, so a blob can't be moved to another engine's slot.
     */
    fun encrypt(plain: String, field: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(field.toByteArray(Charsets.UTF_8))
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Returns null when the blob can't be decrypted (Keystore key wiped by a reset or OEM bug, tampering,
     * unknown version). Callers must treat that as "no key" and ask the user to enter it again; never as plaintext.
     */
    fun decrypt(blob: String, field: String): String? = runCatching {
        require(blob.startsWith(PREFIX))
        val raw = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, raw, 0, 12))
        c.updateAAD(field.toByteArray(Charsets.UTF_8))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
