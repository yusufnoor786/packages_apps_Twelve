/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec

/**
 * Turns the media reference found in the catalog into a URL that can be played.
 */
object SaavnStream {
    /**
     * Streaming bitrates, in kbps.
     */
    enum class Quality(val bitrate: Int) {
        LOW(96),
        MEDIUM(160),
        HIGH(320),
    }

    private val qualitySuffix = Regex("_96(\\.[A-Za-z0-9]+)$")

    private const val KEY = "38346591"

    /**
     * Decrypt the media reference and select the best available rendition not above
     * [maxQuality].
     *
     * @return The playable URL or null if the reference is missing or invalid
     */
    fun playbackUrl(
        encryptedMediaUrl: String?,
        has320kbps: Boolean,
        maxQuality: Quality = Quality.HIGH,
    ): String? {
        if (encryptedMediaUrl.isNullOrBlank()) {
            return null
        }

        val baseUrl = runCatching { decrypt(encryptedMediaUrl) }.getOrNull()
            ?.takeIf { it.startsWith("http") }
            ?: return null

        val quality = when {
            maxQuality == Quality.HIGH && has320kbps -> Quality.HIGH
            maxQuality >= Quality.MEDIUM -> Quality.MEDIUM
            else -> Quality.LOW
        }

        return baseUrl
            .replaceFirst("http://", "https://")
            .replace(qualitySuffix, "_${quality.bitrate}$1")
    }

    private fun decrypt(encrypted: String): String {
        val key = SecretKeyFactory.getInstance("DES").generateSecret(DESKeySpec(KEY.toByteArray()))
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)

        return String(cipher.doFinal(Base64.getDecoder().decode(encrypted)))
    }
}
