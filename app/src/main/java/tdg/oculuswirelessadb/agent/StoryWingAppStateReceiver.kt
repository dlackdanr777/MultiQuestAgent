package tdg.oculuswirelessadb.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StoryWingAppStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MQ-AppState"

        const val ACTION_APP_STATE =
            "com.storywing.multiquest.APP_STATE"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_STATE = "state"

        const val STATE_OPEN = "open"
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED = "paused"
        const val STATE_CLOSED = "closed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APP_STATE) return

        val packageName =
            intent.getStringExtra(EXTRA_PACKAGE_NAME)
                ?.trim()
                .orEmpty()

        val state =
            intent.getStringExtra(EXTRA_STATE)
                ?.trim()
                ?.lowercase()
                .orEmpty()

        if (packageName.isBlank()) {
            Log.w(TAG, "APP_STATE ignored: packageName blank")
            return
        }

        when (state) {
            STATE_OPEN,
            STATE_ACTIVE,
            STATE_PAUSED -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_$state"
                )
            }

            STATE_CLOSED -> {
                LastLaunchedAppStore.markClosed(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_CLOSED"
                )
            }

            else -> {
                Log.w(TAG, "APP_STATE unknown. package=$packageName state=$state")
            }
        }
    }
}