package tdg.oculuswirelessadb.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log

/**
 * Meta Quest의 "기기 권한(Device Permissions)" 페이지를 직접 엽니다.
 *
 * 이 페이지에는 Horizon OS 버전에 따라 다음 항목이 표시됩니다.
 * - 물리적 공간 기록 삭제
 * - Clear physical space history
 *
 * ADB에서 검증한 명령과 동일한 Intent입니다.
 *
 * adb shell am start \
 *   -n com.oculus.panelapp.settings/.SettingsActivity \
 *   --es uri "/device_permissions"
 *
 * 주의:
 * - 이 코드는 삭제 화면까지만 엽니다.
 * - 실제 삭제 버튼과 확인 버튼은 헤드셋에서 사용자가 눌러야 합니다.
 * - Meta 단체 관리 정책이 해당 페이지 실행 자체를 막으면 일반 앱이 우회할 수 없습니다.
 */
object SystemSettingsLauncher {

    private const val TAG = "MQ-PhysicalSpace"

    private const val META_SETTINGS_PACKAGE =
        "com.oculus.panelapp.settings"

    private const val META_SETTINGS_ACTIVITY =
        "com.oculus.panelapp.settings.SettingsActivity"

    const val DEVICE_PERMISSIONS_URI =
        "/device_permissions"

    data class OpenResult(
        val opened: Boolean,
        val uri: String = DEVICE_PERMISSIONS_URI,
        val error: String? = null
    )

    /**
     * 물리적 공간 기록 삭제 항목이 있는 Device Permissions 페이지를 엽니다.
     */
    fun openPhysicalSpaceHistory(
        activity: Activity
    ): OpenResult {
        val intent = Intent().apply {
            setClassName(
                META_SETTINGS_PACKAGE,
                META_SETTINGS_ACTIVITY
            )

            // adb의 --es uri "/device_permissions"와 동일합니다.
            putExtra(
                "uri",
                DEVICE_PERMISSIONS_URI
            )

            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        return try {
            Log.w(
                TAG,
                "Opening Meta Device Permissions. " +
                        "component=$META_SETTINGS_PACKAGE/$META_SETTINGS_ACTIVITY " +
                        "uri=$DEVICE_PERMISSIONS_URI"
            )

            activity.startActivity(intent)

            try {
                activity.overridePendingTransition(0, 0)
            } catch (_: Exception) {
            }

            Log.w(
                TAG,
                "Meta Device Permissions launch dispatched. " +
                        "uri=$DEVICE_PERMISSIONS_URI"
            )

            OpenResult(
                opened = true
            )
        } catch (e: SecurityException) {
            val message =
                "관리 정책 또는 권한으로 차단됨: " +
                        (e.message ?: e.javaClass.simpleName)

            Log.e(
                TAG,
                "Meta Device Permissions blocked",
                e
            )

            OpenResult(
                opened = false,
                error = message
            )
        } catch (e: ActivityNotFoundException) {
            val message =
                "Meta SettingsActivity를 찾지 못했습니다: " +
                        (e.message ?: e.javaClass.simpleName)

            Log.e(
                TAG,
                "Meta SettingsActivity not found",
                e
            )

            OpenResult(
                opened = false,
                error = message
            )
        } catch (e: Exception) {
            val message =
                "설정 화면 실행 실패: " +
                        (e.message ?: e.javaClass.simpleName)

            Log.e(
                TAG,
                "Meta Device Permissions launch failed",
                e
            )

            OpenResult(
                opened = false,
                error = message
            )
        }
    }
}