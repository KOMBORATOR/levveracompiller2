package com.levver.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores VPN profiles encrypted with an AES-256-GCM key held by Android Keystore. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("levver_profiles", Context.MODE_PRIVATE)
    private val alias = "levver.profile.store.aes.v1"
    private val prefix = "enc:v1:"

    @Synchronized
    fun load(): List<Profile> {
        val stored = prefs.getString("items", null) ?: return emptyList()
        return try {
            val json = if (stored.startsWith(prefix)) {
                decrypt(stored.removePrefix(prefix))
            } else {
                // Migrate early prototype data from plaintext storage.
                val legacy = decodeProfiles(stored)
                save(legacy)
                stored
            }
            decodeProfiles(json)
        } catch (_: Exception) {
            // Do not overwrite unreadable encrypted data.
            emptyList()
        }
    }

    @Synchronized
    fun save(items: List<Profile>) {
        val array = JSONArray()
        items.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("protocol", profile.protocol)
                .put("raw", profile.raw)
                .put("server", profile.server))
        }
        prefs.edit().putString("items", prefix + encrypt(array.toString())).apply()
    }

    private fun decodeProfiles(json: String): List<Profile> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Profile(item.getString("id"), item.getString("name"), item.getString("protocol"),
                item.getString("raw"), item.getString("server"))
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val ivLength = payload.int
        require(ivLength in 12..16)
        val iv = ByteArray(ivLength).also(payload::get)
        val encrypted = ByteArray(payload.remaining()).also(payload::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
