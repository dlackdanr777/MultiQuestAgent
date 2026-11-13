package tdg.oculuswirelessadb.agent

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Agent 기기 이름의 단일 진실 공급원입니다.
 *
 * 보존 계층:
 * 1. 일반 SharedPreferences
 * 2. Device Protected Storage SharedPreferences
 * 3. filesDir JSON
 * 4. noBackupFilesDir JSON
 * 5. 기존 v1 SharedPreferences 마이그레이션
 *
 * 같은 applicationId/서명으로 수행되는 정상적인 replace update에서는 위 데이터가
 * 유지됩니다. 설치 과정에서 uninstall/clear-data가 실행되면 Android app-private
 * 데이터는 삭제되므로, 그 경우 WPF의 deviceId 기반 이름 캐시가 /setDeviceName으로
 * 자동 복구합니다.
 */
object AgentNameStore {

    private const val TAG = "MQ-AgentName"

    private const val PREF_V2 = "multiquest_agent_identity_v2"
    private const val PREF_LEGACY = "multiquest_agent_identity"

    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_IS_CUSTOM = "is_custom"
    private const val KEY_UPDATED_AT = "updated_at_ms"

    private const val FILE_NAME = "agent_identity_v2.json"
    private const val MAX_NAME_LENGTH = 64

    data class Identity(
        val name: String,
        val isCustom: Boolean,
        val source: String,
        val updatedAtMs: Long
    )

    private val lock = Any()

    @Volatile
    private var cachedIdentity: Identity? = null

    fun getIdentity(context: Context): Identity {
        cachedIdentity?.let { return it }

        synchronized(lock) {
            cachedIdentity?.let { return it }

            val appContext = context.applicationContext
            val candidates = mutableListOf<Identity>()

            readFromPreferences(appContext, PREF_V2, "credential_prefs")
                ?.let(candidates::add)

            deviceProtectedContext(appContext)?.let { deviceContext ->
                readFromPreferences(
                    deviceContext,
                    PREF_V2,
                    "device_protected_prefs"
                )?.let(candidates::add)
            }

            readFromJsonFile(
                File(appContext.filesDir, FILE_NAME),
                "files_dir"
            )?.let(candidates::add)

            readFromJsonFile(
                File(appContext.noBackupFilesDir, FILE_NAME),
                "no_backup_dir"
            )?.let(candidates::add)

            readLegacy(appContext)?.let(candidates::add)

            val best = candidates
                .filter { it.isCustom && isValidName(it.name) }
                .maxWithOrNull(
                    compareBy<Identity> { it.updatedAtMs }
                        .thenBy { sourcePriority(it.source) }
                )

            val resolved = best ?: Identity(
                name = "",
                isCustom = false,
                source = "generated",
                updatedAtMs = 0L
            )

            cachedIdentity = resolved

            if (resolved.isCustom) {
                writeEverywhere(appContext, resolved)
            }

            Log.i(
                TAG,
                "Identity loaded. custom=${resolved.isCustom} " +
                    "source=${resolved.source} name='${resolved.name}'"
            )

            return resolved
        }
    }

    fun getDeviceName(context: Context): String {
        return getIdentity(context).name
    }

    fun getDeviceNameSource(context: Context): String {
        return if (getIdentity(context).isCustom) {
            "custom"
        } else {
            "generated"
        }
    }

    fun getUpdatedAtMs(context: Context): Long {
        return getIdentity(context).updatedAtMs
    }

    /**
     * 기존 호출부 호환용입니다. 공백은 우발적인 TextWatcher 초기화로 보고 무시합니다.
     */
    fun setDeviceName(
        context: Context,
        name: String
    ): Boolean {
        val normalized = normalizeName(name) ?: return false

        synchronized(lock) {
            val identity = Identity(
                name = normalized,
                isCustom = true,
                source = "custom",
                updatedAtMs = System.currentTimeMillis()
            )

            writeEverywhere(context.applicationContext, identity)
            cachedIdentity = identity

            Log.i(TAG, "Custom device name saved: '$normalized'")
            return true
        }
    }

    fun clearDeviceName(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext

            clearPreferences(appContext, PREF_V2)
            clearPreferences(appContext, PREF_LEGACY)

            deviceProtectedContext(appContext)?.let { deviceContext ->
                clearPreferences(deviceContext, PREF_V2)
                clearPreferences(deviceContext, PREF_LEGACY)
            }

            deleteQuietly(File(appContext.filesDir, FILE_NAME))
            deleteQuietly(File(appContext.noBackupFilesDir, FILE_NAME))

            cachedIdentity = Identity(
                name = "",
                isCustom = false,
                source = "generated",
                updatedAtMs = 0L
            )

            Log.w(TAG, "Custom device name cleared")
        }
    }

    /** 저장소 중 하나만 남아 있어도 나머지 저장소를 복구합니다. */
    fun repair(context: Context): Identity {
        synchronized(lock) {
            cachedIdentity = null
        }
        return getIdentity(context)
    }

    private fun writeEverywhere(
        context: Context,
        identity: Identity
    ) {
        writePreferences(context, PREF_V2, identity)

        deviceProtectedContext(context)?.let { deviceContext ->
            writePreferences(deviceContext, PREF_V2, identity)
        }

        writeJsonFile(
            File(context.filesDir, FILE_NAME),
            identity
        )

        writeJsonFile(
            File(context.noBackupFilesDir, FILE_NAME),
            identity
        )

        // 구버전 APK로 롤백하더라도 이름을 읽을 수 있도록 legacy key도 유지합니다.
        context.getSharedPreferences(
            PREF_LEGACY,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_DEVICE_NAME, identity.name)
            .commit()
    }

    private fun writePreferences(
        context: Context,
        prefName: String,
        identity: Identity
    ) {
        try {
            context.getSharedPreferences(
                prefName,
                Context.MODE_PRIVATE
            ).edit()
                .putString(KEY_DEVICE_NAME, identity.name)
                .putBoolean(KEY_IS_CUSTOM, identity.isCustom)
                .putLong(KEY_UPDATED_AT, identity.updatedAtMs)
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "writePreferences failed: $prefName", e)
        }
    }

    private fun readFromPreferences(
        context: Context,
        prefName: String,
        source: String
    ): Identity? {
        return try {
            val prefs = context.getSharedPreferences(
                prefName,
                Context.MODE_PRIVATE
            )

            val name = normalizeName(
                prefs.getString(KEY_DEVICE_NAME, "")
            ) ?: return null

            val custom = prefs.getBoolean(
                KEY_IS_CUSTOM,
                true
            )

            Identity(
                name = name,
                isCustom = custom,
                source = source,
                updatedAtMs = prefs.getLong(
                    KEY_UPDATED_AT,
                    0L
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "readFromPreferences failed: $prefName", e)
            null
        }
    }

    private fun readLegacy(context: Context): Identity? {
        return try {
            val name = normalizeName(
                context.getSharedPreferences(
                    PREF_LEGACY,
                    Context.MODE_PRIVATE
                ).getString(KEY_DEVICE_NAME, "")
            ) ?: return null

            Identity(
                name = name,
                isCustom = true,
                source = "legacy_prefs",
                updatedAtMs = 1L
            )
        } catch (e: Exception) {
            Log.w(TAG, "readLegacy failed", e)
            null
        }
    }

    private fun writeJsonFile(
        target: File,
        identity: Identity
    ) {
        try {
            target.parentFile?.mkdirs()

            val json = JSONObject()
                .put("deviceName", identity.name)
                .put("isCustom", identity.isCustom)
                .put("updatedAtMs", identity.updatedAtMs)

            val temp = File(
                target.parentFile,
                target.name + ".tmp"
            )

            temp.writeText(json.toString(), Charsets.UTF_8)

            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not delete old identity file: ${target.path}")
            }

            if (!temp.renameTo(target)) {
                target.writeText(json.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeJsonFile failed: ${target.path}", e)
        }
    }

    private fun readFromJsonFile(
        file: File,
        source: String
    ): Identity? {
        if (!file.exists() || !file.isFile) return null

        return try {
            val json = JSONObject(
                file.readText(Charsets.UTF_8)
            )

            val name = normalizeName(
                json.optString("deviceName", "")
            ) ?: return null

            Identity(
                name = name,
                isCustom = json.optBoolean("isCustom", true),
                source = source,
                updatedAtMs = json.optLong("updatedAtMs", 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "readJsonFile failed: ${file.path}", e)
            null
        }
    }

    private fun normalizeName(value: String?): String? {
        if (value.isNullOrBlank()) return null

        val normalized = buildString {
            value.trim().forEach { ch ->
                if (!ch.isISOControl() && length < MAX_NAME_LENGTH) {
                    append(ch)
                }
            }
        }.trim()

        return normalized.ifBlank { null }
    }

    private fun isValidName(value: String): Boolean {
        return normalizeName(value) != null
    }

    private fun sourcePriority(source: String): Int {
        return when (source) {
            "credential_prefs" -> 5
            "device_protected_prefs" -> 4
            "no_backup_dir" -> 3
            "files_dir" -> 2
            "legacy_prefs" -> 1
            else -> 0
        }
    }

    private fun deviceProtectedContext(context: Context): Context? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null

        return try {
            context.createDeviceProtectedStorageContext()
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPreferences(
        context: Context,
        prefName: String
    ) {
        try {
            context.getSharedPreferences(
                prefName,
                Context.MODE_PRIVATE
            ).edit().clear().commit()
        } catch (_: Exception) {
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
