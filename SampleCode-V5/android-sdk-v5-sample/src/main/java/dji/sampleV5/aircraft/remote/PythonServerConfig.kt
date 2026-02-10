package dji.sampleV5.aircraft.remote

import android.content.Context

data class PythonServerConfig(val host: String, val port: Int) {
    fun baseUrl(): String = "http://$host:$port"
}

object PythonServerConfigStore {
    private const val PREF = "python_server_cfg"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    fun get(ctx: Context): PythonServerConfig {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val host = sp.getString(KEY_HOST, "192.168.1.49")!!.trim()
        val port = sp.getInt(KEY_PORT, 8080)
        return PythonServerConfig(host, port)
    }

    fun set(ctx: Context, host: String, port: Int) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
    }
}
