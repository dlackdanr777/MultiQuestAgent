package tdg.oculuswirelessadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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

        // 부팅 직후 자동 캡처 지연 10초
        private const val BOOT_AUTO_CAPTURE_DELAY_MS = 7_000L

        // 앱 업데이트 직후 자동 캡처 지연 2분
        private const val AFTER_PACKAGE_REPLACED_DELAY_MS = 2 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Log.i(TAG, "System event received: $action")

        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                handlePackageReplaced(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }

            else -> {
                Log.d(TAG, "Ignored action: $action")
            }
        }
    }

    private fun handlePackageReplaced(context: Context) {
        Log.w(TAG, "App package replaced. Pausing auto capture temporarily.")

        try {
            CaptureService.stop(context)
        } catch (e: Exception) {
            Log.e(TAG, "CaptureService stop failed after package replaced", e)
        }

        CaptureStateStore.clearToIdle()

        // 업데이트 직후에는 먼저 pause를 걸고
        // 그 다음 AgentService를 시작합니다.
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
        val bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastBootEpoch = prefs.getLong(KEY_LAST_BOOT_EPOCH, -1L)

        if (lastBootEpoch > 0 &&
            abs(lastBootEpoch - bootEpoch) < BOOT_EPOCH_TOLERANCE_MS
        ) {
            Log.i(TAG, "Already handled this boot epoch. Skip duplicate boot event.")
            return
        }

        prefs.edit()
            .putLong(KEY_LAST_BOOT_EPOCH, bootEpoch)
            .apply()

        // 중요:
        // AgentService.start보다 pause가 먼저여야 합니다.
        AutoCapturePauseStore.pause(
            context = context,
            durationMs = BOOT_AUTO_CAPTURE_DELAY_MS,
            reason = "BOOT_GRACE_10_SECONDS"
        )

        Log.i(TAG, "Boot handled. Auto capture delayed for 10 seconds.")

        try {
            AgentService.start(context)
            Log.i(TAG, "AgentService started on boot after pause was set")
        } catch (e: Exception) {
            Log.e(TAG, "AgentService start failed on boot", e)
        }
    }
}