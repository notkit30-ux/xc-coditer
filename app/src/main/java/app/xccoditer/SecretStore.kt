package app.xccoditer

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keeps the Git token encrypted with an Android Keystore key. If encryption is unavailable the token is not stored. */
class SecretStore(ctx: Context) {
    private val prefs = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "xc_secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    val available: Boolean get() = prefs != null
    fun get(k: String): String? = runCatching { prefs?.getString(k, null) }.getOrNull()
    fun put(k: String, v: String): Boolean = runCatching { prefs?.edit()?.putString(k, v)?.apply() != null }.getOrDefault(false)
    fun remove(k: String) { runCatching { prefs?.edit()?.remove(k)?.apply() } }
}
