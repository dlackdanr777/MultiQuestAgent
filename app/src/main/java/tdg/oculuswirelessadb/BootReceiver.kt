package tdg.oculuswirelessadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import tdg.oculuswirelessadb.agent.AgentNameStore
import tdg.oculuswirelessadb.agent.AgentService
import tdg.oculuswirelessadb.agent.AutoCapturePauseStore
import tdg.oculuswirelessadb.capture.CaptureService
import tdg.oculuswirelessadb.capture.CaptureStateStore
import kotlin.math.abs

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OW-BootReceiver"

        private const val PREFS = "mq_boot_receiver"
        private const val KEY_LAST_BOOT_EPOCH = "last_boot_epoch"
        private const val BOOT_EPOCH_TOLERANCE_MS = 60_000L

        private const val BOOT_AUTO_CAPTURE_DELAY_MS = 7_000L
        private const val AFTER_PACKAGE_REPLACED_DELAY_MS = 2 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.i(TAG, "System event received: $action")

        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                handlePackageReplaced(context)

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                handleBootCompleted(context)

            else -> Log.d(TAG, "Ignored action: $action")
        }
    }

    private fun handlePackageReplaced(context: Context) {
        Log.w(TAG, "App package replaced. Repair identity and pause capture temporarily.")

        try {
            val identity = AgentNameStore.repair(context)
            Log.i(
                TAG,
                "Agent name repaired after update. " +
                    "custom=${identity.isCustom} source=${identity.source} " +
                    "name='${identity.name}'"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AgentNameStore repair failed after update", e)
        }

        try {
            CaptureService.stop(context)
        } catch (e: Exception) {
            Log.e(TAG, "CaptureService stop failed after package replaced", e)
        }

        CaptureStateStore.clearToIdle()

        AutoCapturePauseStore.pause(
            context = context,
            durationMs = AFTER_PACKAGE_REPLACED_DELAY_MS,
            reason = "MY_PACKAGE_REPLACED"
        )

        try {
            AgentService.start(context)
            Log.i(TAG, "AgentService started after package replaced")
        } catch (e: Exception) {
            Log.e(TAG, "AgentService start failed after package replaced", e)
        }
    }

    private fun handleBootCompleted(context: Context) {
        try {
            AgentNameStore.repair(context)
        } catch (e: Exception) {
            Log.w(TAG, "Agent name repair failed on boot", e)
        }

        val bootEpoch =
            System.currentTimeMillis() - SystemClock.elapsedRealtime()

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val lastBootEpoch = prefs.getLong(
            KEY_LAST_BOOT_EPOCH,
            -1L
        )

        if (lastBootEpoch > 0L &&
            abs(lastBootEpoch - bootEpoch) < BOOT_EPOCH_TOLERANCE_MS
        ) {
            Log.i(TAG, "Already handled this boot epoch. Skip duplicate boot event.")
            return
        }

        prefs.edit()
            .putLong(KEY_LAST_BOOT_EPOCH, bootEpoch)
            .apply()

        AutoCapturePauseStore.pause(
            context = context,
            durationMs = BOOT_AUTO_CAPTURE_DELAY_MS,
            reason = "BOOT_GRACE_7_SECONDS"
        )

        try {
            AgentService.start(context)
            Log.i(TAG, "AgentService started on boot after pause was set")
        } catch (e: Exception) {
            Log.e(TAG, "AgentService start failed on boot", e)
        }
    }
}
