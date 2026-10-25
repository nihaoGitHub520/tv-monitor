package com.tvmonitor.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tvmonitor.util.TvLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密器（NFR-05：ONVIF 账号密码本地加密存储，不明文落盘）。
 *
 * 方案：Android Keystore 硬件 backed AES-256-GCM。
 * - 密钥永不出 Keystore（`setKeySize(256)` + GCM + 无 padding）；
 * - 密文格式：`v1:<Base64(IV)>:<Base64(密文)>`，与明文一同落盘也无可读性；
 * - GCM 认证标签 128bit，篡改即解密失败；
 * - 若系统因锁屏凭据变更使密钥失效，删除并重建（旧密文不可恢复，调用方需引导重新输入）。
 *
 * 线程约定：Keystore 操作可重入，允许多线程调用；异常统一抛 [CredentialCryptoException]。
 */
class CredentialCrypto {

    /**
     * 加密明文密码。
     * @return `v1:<iv>:<ct>` 格式密文；入参为空串时返回空串（不加密空值）。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:${b64(iv)}:${b64(cipherText)}"
        } catch (e: Exception) {
            TvLog.e(TAG, "凭据加密失败", e)
            throw CredentialCryptoException("凭据加密失败", e)
        }
    }

    /**
     * 解密 [encrypt] 产出的密文。
     * @return 明文密码；入参为空串时返回空串。
     * @throws CredentialCryptoException 密钥失效 / 密文被篡改 / 版本不兼容。
     */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val parts = encoded.split(SEP)
            require(parts.size == 3 && parts[0] == VERSION) { "密文格式不正确" }
            val iv = unb64(parts[1])
            val cipherText = unb64(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            TvLog.e(TAG, "凭据解密失败（密钥可能已失效）", e)
            throw CredentialCryptoException("凭据解密失败，请重新输入密码", e)
        }
    }

    /** 解密失败不抛异常版本（UI 兜底：视为无密码）。 */
    fun decryptOrNull(encoded: String): String? =
        if (encoded.isEmpty()) "" else runCatching { decrypt(encoded) }.getOrNull()

    /** 取 Keystore 密钥，不存在则创建。 */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 需要用户认证的设备上不强制锁屏认证：监控大屏通常无交互解锁
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val TAG = "TvMonitor:CredentialCrypto"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "tvmonitor.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val VERSION = "v1"
        private const val SEP = ":"
    }
}

/** 凭据加解密异常（消息面向用户，可直接提示）。 */
class CredentialCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
