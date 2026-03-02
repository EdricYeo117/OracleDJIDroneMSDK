package dji.sampleV5.aircraft.remote

/**
 * Remote module file `PythonServerConfig.kt`: contains PythonServerConfig implementation details.
 */

import android.content.Context
import dji.sampleV5.aircraft.R

data class PythonServerConfig(
    val host: String,
    val port: Int,
    val apiKey: String? = null
) {
    // Handles `baseUrl` behavior for the remote control module.
    fun baseUrl(): String = "http://$host:$port"
}

object PythonServerConfigStore {
    private const val PREF = "python_server_cfg"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_API_KEY = "api_key"   // NEW

    // Handles `get` behavior for the remote control module.
    fun get(ctx: Context): PythonServerConfig {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        val host = sp.getString(KEY_HOST, "192.168.1.49")!!.trim()
        val port = sp.getInt(KEY_PORT, 8080)

        // Prefer saved, else fallback to strings.xml default if you add one
        val savedKey = sp.getString(KEY_API_KEY, null)?.trim().takeIf { !it.isNullOrBlank() }
        val defaultKey = runCatching {
            ctx.getString(R.string.controller_api_key_default).trim()
        }.getOrNull().takeIf { !it.isNullOrBlank() }

        val apiKey = savedKey ?: defaultKey

        return PythonServerConfig(host, port, apiKey)
    }

    // Backwards-compatible setter (keeps existing calls working)
    fun set(ctx: Context, host: String, port: Int) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
    }

    // NEW: setter for API key
    fun setApiKey(ctx: Context, apiKey: String?) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = apiKey?.trim()
        sp.edit()
            .putString(KEY_API_KEY, v)
            .apply()
    }
}
