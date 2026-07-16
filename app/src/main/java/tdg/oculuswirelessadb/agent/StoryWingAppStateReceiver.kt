package tdg.oculuswirelessadb.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tdg.oculuswirelessadb.KeepAwakeActivity

class StoryWingAppStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MQ-AppState"

        const val ACTION_APP_STATE = "com.storywing.multiquest.APP_STATE"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_STATE = "state"

        const val STATE_OPEN = "open"
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED = "paused"
        const val STATE_CLOSED = "closed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APP_STATE) return

        val packageName = intent
            .getStringExtra(EXTRA_PACKAGE_NAME)
            ?.trim()
            .orEmpty()

        val state = intent
            .getStringExtra(EXTRA_STATE)
            ?.trim()
            ?.lowercase()
            .orEmpty()

        if (packageName.isBlank()) {
            Log.w(TAG, "APP_STATE ignored: packageName blank")
            return
        }

        if (!packageName.startsWith("com.StoryWing.", ignoreCase = true)) {
            Log.w(TAG, "APP_STATE ignored: unexpected package=$packageName")
            return
        }

        when (state) {
            STATE_OPEN -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_OPEN"
                )
            }

            STATE_ACTIVE -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_ACTIVE"
                )

                AgentOperationStore.completeLatestLaunchForPackage(
                    packageName = packageName,
                    appState = state
                )

                // StoryWing 앱이 실제 전면에 올라온 뒤 자동 캡처 복구 억제를 해제합니다.
                AppLaunchGuard.clearFor(packageName)

                // 화면을 깨울 때 사용한 투명 보조 Activity가 태스크 맨 앞에 남지 않게 합니다.
                KeepAwakeActivity.finishCurrent()

                Log.i(
                    TAG,
                    "StoryWing ACTIVE confirmed. launch guard cleared and KeepAwakeActivity closed. " +
                            "package=$packageName"
                )
            }

            STATE_PAUSED -> {
                LastLaunchedAppStore.markOpen(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_PAUSED"
                )
            }

            STATE_CLOSED -> {
                val launchGuardMatches =
                    AppLaunchGuard.isActive() &&
                            AppLaunchGuard.packageName()
                                .equals(packageName, ignoreCase = true)

                if (launchGuardMatches) {
                    // forceRestart 직후 이전 Activity가 늦게 보낸 CLOSED가
                    // 새 launch operation을 실패 처리하지 않게 무시합니다.
                    Log.i(
                        TAG,
                        "Stale CLOSED ignored during new launch guard. package=$packageName"
                    )
                    return
                }

                LastLaunchedAppStore.markClosed(
                    context = context,
                    packageName = packageName,
                    reason = "APP_STATE_CLOSED"
                )

                AppLaunchGuard.clearFor(packageName)

                AgentOperationStore.failLatestLaunchForPackage(
                    packageName = packageName,
                    message = "APP_STATE_CLOSED_BEFORE_ACTIVE",
                    retryable = true
                )
            }

            else -> {
                Log.w(
                    TAG,
                    "APP_STATE unknown. package=$packageName state=$state"
                )
            }
        }
    }
}