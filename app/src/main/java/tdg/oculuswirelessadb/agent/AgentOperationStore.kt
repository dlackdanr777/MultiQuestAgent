package tdg.oculuswirelessadb.agent

import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/**
 * HTTP 명령의 "요청 접수"와 "실제 완료"를 구분하기 위한 메모리 작업 저장소입니다.
 *
 * 앱 실행은 StoryWing APP_STATE 방송으로, 캡처 시작은 첫 인코딩 프레임으로 완료됩니다.
 * 프로세스가 재시작되면 작업 이력은 초기화되며, WPF는 해당 경우 /status로 실제 상태를 재판정합니다.
 */
object AgentOperationStore {

    const val TYPE_LAUNCH = "launch"
    const val TYPE_STOP = "stop"
    const val TYPE_STOP_ALL = "stopAllStoryWing"
    const val TYPE_CAPTURE_START = "captureStart"
    const val TYPE_CAPTURE_RESTART = "captureRestart"
    const val TYPE_WAKE = "wake"

    const val STATE_ACCEPTED = "ACCEPTED"
    const val STATE_DISPATCHED = "DISPATCHED"
    const val STATE_WAITING_PERMISSION = "WAITING_PERMISSION"
    const val STATE_STARTING = "STARTING"
    const val STATE_COMPLETED = "COMPLETED"
    const val STATE_FAILED = "FAILED"
    const val STATE_CANCELLED = "CANCELLED"

    private const val MAX_OPERATIONS = 100

    private data class MutableOperation(
        val id: String,
        val type: String,
        val target: String,
        val reason: String,
        val acceptedAtMs: Long,
        var state: String,
        var updatedAtMs: Long,
        var completedAtMs: Long,
        var retryable: Boolean,
        var message: String,
        var detailsJson: String
    )

    private val lock = Any()
    private val operations = LinkedHashMap<String, MutableOperation>()
    private var latestOperationId: String = ""

    fun begin(
        type: String,
        target: String = "",
        reason: String = ""
    ): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        operations[id] = MutableOperation(
            id = id,
            type = type.trim(),
            target = target.trim(),
            reason = reason.trim(),
            acceptedAtMs = now,
            state = STATE_ACCEPTED,
            updatedAtMs = now,
            completedAtMs = 0L,
            retryable = false,
            message = "",
            detailsJson = ""
        )

        latestOperationId = id
        pruneLocked()
        id
    }

    fun markDispatched(
        operationId: String,
        message: String = "",
        details: JSONObject? = null
    ) {
        update(
            operationId = operationId,
            state = STATE_DISPATCHED,
            message = message,
            retryable = false,
            details = details
        )
    }

    fun markWaitingPermission(
        operationId: String,
        message: String = ""
    ) {
        update(
            operationId = operationId,
            state = STATE_WAITING_PERMISSION,
            message = message,
            retryable = true
        )
    }

    fun markStarting(
        operationId: String,
        message: String = ""
    ) {
        update(
            operationId = operationId,
            state = STATE_STARTING,
            message = message,
            retryable = true
        )
    }

    fun complete(
        operationId: String,
        message: String = "",
        details: JSONObject? = null
    ) {
        update(
            operationId = operationId,
            state = STATE_COMPLETED,
            message = message,
            retryable = false,
            details = details
        )
    }

    fun fail(
        operationId: String,
        message: String,
        retryable: Boolean,
        details: JSONObject? = null
    ) {
        update(
            operationId = operationId,
            state = STATE_FAILED,
            message = message,
            retryable = retryable,
            details = details
        )
    }

    fun cancel(
        operationId: String,
        message: String = "cancelled"
    ) {
        update(
            operationId = operationId,
            state = STATE_CANCELLED,
            message = message,
            retryable = false
        )
    }

    fun stateOf(operationId: String): String? = synchronized(lock) {
        operations[operationId]?.state
    }

    fun isPending(operationId: String): Boolean = synchronized(lock) {
        val state = operations[operationId]?.state ?: return@synchronized false
        !isTerminalState(state)
    }

    fun getJson(operationId: String): JSONObject? = synchronized(lock) {
        operations[operationId]?.let(::toJsonLocked)
    }

    fun latestJson(): JSONObject = synchronized(lock) {
        operations[latestOperationId]?.let(::toJsonLocked)
            ?: JSONObject()
                .put("exists", false)
    }

    fun latestPendingCaptureOperationId(): String = synchronized(lock) {
        operations.values
            .toList()
            .asReversed()
            .firstOrNull {
                (it.type == TYPE_CAPTURE_START || it.type == TYPE_CAPTURE_RESTART) &&
                        !isTerminalState(it.state)
            }
            ?.id
            .orEmpty()
    }

    fun completeLatestLaunchForPackage(
        packageName: String,
        appState: String
    ) = synchronized(lock) {
        val operation = operations.values
            .toList()
            .asReversed()
            .firstOrNull {
                it.type == TYPE_LAUNCH &&
                        it.target.equals(packageName, ignoreCase = true) &&
                        !isTerminalState(it.state)
            }
            ?: return@synchronized

        val now = System.currentTimeMillis()
        operation.state = STATE_COMPLETED
        operation.updatedAtMs = now
        operation.completedAtMs = now
        operation.retryable = false
        operation.message = "APP_STATE_$appState"
    }

    fun failLatestLaunchForPackage(
        packageName: String,
        message: String,
        retryable: Boolean
    ) = synchronized(lock) {
        val operation = operations.values
            .toList()
            .asReversed()
            .firstOrNull {
                it.type == TYPE_LAUNCH &&
                        it.target.equals(packageName, ignoreCase = true) &&
                        !isTerminalState(it.state)
            }
            ?: return@synchronized

        val now = System.currentTimeMillis()
        operation.state = STATE_FAILED
        operation.updatedAtMs = now
        operation.completedAtMs = now
        operation.retryable = retryable
        operation.message = message
    }

    fun failPendingCapture(
        message: String,
        retryable: Boolean
    ) {
        val id = latestPendingCaptureOperationId()
        if (id.isNotBlank()) {
            fail(id, message, retryable)
        }
    }

    fun cancelPendingLaunches(
        message: String = "superseded_by_new_launch"
    ) = synchronized(lock) {
        val now = System.currentTimeMillis()

        operations.values.forEach { operation ->
            if (operation.type == TYPE_LAUNCH &&
                !isTerminalState(operation.state)
            ) {
                operation.state = STATE_CANCELLED
                operation.updatedAtMs = now
                operation.completedAtMs = now
                operation.retryable = false
                operation.message = message
            }
        }
    }

    fun cancelPendingLaunchForPackage(
        packageName: String,
        message: String = "launch_cancelled_by_stop"
    ) = synchronized(lock) {
        val now = System.currentTimeMillis()

        operations.values.forEach { operation ->
            if (operation.type == TYPE_LAUNCH &&
                operation.target.equals(packageName, ignoreCase = true) &&
                !isTerminalState(operation.state)
            ) {
                operation.state = STATE_CANCELLED
                operation.updatedAtMs = now
                operation.completedAtMs = now
                operation.retryable = false
                operation.message = message
            }
        }
    }

    private fun update(
        operationId: String,
        state: String,
        message: String,
        retryable: Boolean,
        details: JSONObject? = null
    ) = synchronized(lock) {
        if (operationId.isBlank()) return@synchronized

        val operation = operations[operationId] ?: return@synchronized
        if (isTerminalState(operation.state)) return@synchronized

        val now = System.currentTimeMillis()
        operation.state = state
        operation.updatedAtMs = now
        operation.retryable = retryable
        operation.message = message.trim()

        if (details != null) {
            operation.detailsJson = details.toString()
        }

        if (isTerminalState(state)) {
            operation.completedAtMs = now
        }
    }

    private fun toJsonLocked(operation: MutableOperation): JSONObject {
        val details = if (operation.detailsJson.isBlank()) {
            JSONObject()
        } else {
            try {
                JSONObject(operation.detailsJson)
            } catch (_: Exception) {
                JSONObject()
            }
        }

        return JSONObject()
            .put("exists", true)
            .put("operationId", operation.id)
            .put("type", operation.type)
            .put("target", operation.target)
            .put("reason", operation.reason)
            .put("state", operation.state)
            .put("accepted", true)
            .put("completed", operation.state == STATE_COMPLETED)
            .put("failed", operation.state == STATE_FAILED)
            .put("cancelled", operation.state == STATE_CANCELLED)
            .put("retryable", operation.retryable)
            .put("message", operation.message)
            .put("acceptedAtMs", operation.acceptedAtMs)
            .put("updatedAtMs", operation.updatedAtMs)
            .put("completedAtMs", operation.completedAtMs)
            .put("details", details)
    }

    private fun pruneLocked() {
        if (operations.size <= MAX_OPERATIONS) return

        val iterator = operations.entries.iterator()
        while (operations.size > MAX_OPERATIONS && iterator.hasNext()) {
            val entry = iterator.next()
            if (isTerminalState(entry.value.state)) {
                iterator.remove()
            }
        }

        while (operations.size > MAX_OPERATIONS) {
            val firstKey = operations.keys.firstOrNull() ?: break
            operations.remove(firstKey)
        }
    }

    private fun isTerminalState(state: String): Boolean {
        return state == STATE_COMPLETED ||
                state == STATE_FAILED ||
                state == STATE_CANCELLED
    }
}