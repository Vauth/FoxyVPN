package com.vauth.foxyvpn.data

import android.content.Context
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class SecurePreferences(
    context: Context,
    private val legacyName: String,
    private val failure: (Exception) -> SecureStorageException = { SecureStorageException(it) },
) {
    private val context = context.applicationContext
    private val namespace = "${context.packageName}.$legacyName.v1"
    private val prefs =
        context.getSharedPreferences("${legacyName}_secure_v1", Context.MODE_PRIVATE)
    private val lock = locks.getOrPut(namespace) { Any() }
    private var cachedPayload: String? = null
    private var cachedValues: Map<String, Any>? = null

    fun getString(key: String, default: String?): String? = guarded {
        val value = load()[key] ?: return@guarded default
        check(value is String) { "Unexpected credential value type" }
        value
    }

    fun getLong(key: String, default: Long): Long = guarded {
        val value = load()[key] ?: return@guarded default
        check(value is Long) { "Unexpected credential value type" }
        value
    }

    fun edit() = Editor()

    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        fun putString(key: String, value: String?) = apply { changes[key] = value }

        fun putLong(key: String, value: Long) = apply { changes[key] = value }

        fun remove(key: String) = apply { changes[key] = null }

        fun clear() = apply { clear = true }

        fun commit() = guarded {
            val current = load()
            val next = if (clear) mutableMapOf() else current.toMutableMap()
            for ((key, value) in changes) if (value == null) next.remove(key) else next[key] = value
            write(next)
        }
    }

    private fun <T> guarded(action: () -> T): T =
        synchronized(lock) {
            try {
                action()
            } catch (error: Exception) {
                cachedPayload = null
                cachedValues = null
                throw error as? SecureStorageException ?: failure(error)
            }
        }

    private fun load(): Map<String, Any> {
        val payload = prefs.getString(PAYLOAD, null)
        if (payload != null) {
            if (payload == cachedPayload) return checkNotNull(cachedValues)
            return decode(payload).also {
                cachedPayload = payload
                cachedValues = it
            }
        }
        check(prefs.all.isEmpty()) { "Encrypted credential payload is missing" }
        val rawLegacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (rawLegacy.all.isEmpty()) return emptyMap()
        val legacy = LegacyCredentialReader.open(context, legacyName)
        val values =
            legacy.all.mapValues { (_, value) ->
                check(value is String || value is Long) {
                    "Unsupported legacy credential value type"
                }
                checkNotNull(value)
            }
        write(values)
        check(decode(checkNotNull(prefs.getString(PAYLOAD, null))) == values) {
            "Credential migration verification failed"
        }
        if (!legacy.edit().clear().commit())
            AppLogger.w(
                "SecurePreferences",
                "Legacy credential cleanup could not be persisted; verified encrypted copy retained",
            )
        return values
    }

    private fun write(values: Map<String, Any>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            CredentialKeys.get(namespace, create = !prefs.contains(PAYLOAD)),
        )
        cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12) { "Invalid encryption IV" }
        val ciphertext = cipher.doFinal(JSONObject(values).toString().toByteArray(Charsets.UTF_8))
        val payload =
            "1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        check(payload.length <= MAX_PAYLOAD_CHARS) { "Credential payload is too large" }
        val editor = prefs.edit().putString(PAYLOAD, payload)
        check(editor.commit()) { "Could not persist encrypted credentials" }
        cachedPayload = payload
        cachedValues = values.toMap()
    }

    private fun decode(payload: String): Map<String, Any> {
        check(payload.length <= MAX_PAYLOAD_CHARS) { "Credential payload is too large" }
        val parts = payload.split(':')
        check(parts.size == 3 && parts[0] == "1") { "Unsupported credential format" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        check(iv.size == 12 && ciphertext.size >= 16) { "Invalid credential payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            CredentialKeys.get(namespace, create = false),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is String -> value
                is Number -> value.toLong()
                else -> error("Unexpected credential value type")
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val PAYLOAD = "encrypted_payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_PAYLOAD_CHARS = 256 * 1024
    }
}
