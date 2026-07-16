package tdg.oculuswirelessadb.agent

import android.content.Context

object AgentNameStore {
    private const val PREF_NAME = "multiquest_agent_identity"
    private const val KEY_DEVICE_NAME = "device_name"

    fun getDeviceName(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_NAME, "")
            ?.trim()
            ?: ""
    }

    fun setDeviceName(context: Context, name: String) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, name.trim())
            .apply()
    }
}