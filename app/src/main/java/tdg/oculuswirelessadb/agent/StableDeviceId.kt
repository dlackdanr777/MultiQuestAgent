package tdg.oculuswirelessadb.agent

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object StableDeviceId {
    /**
     * Android ID를 기반으로 SHA-256 해싱된 16자리 고유 ID를 반환합니다.
     * 앱 삭제 후 재설치해도 동일한 서명 키를 사용한다면 유지됩니다.
     */
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val raw = "mq:${androidId}"
        return sha256(raw).take(16).uppercase()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}