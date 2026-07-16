package tdg.oculuswirelessadb.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AgentLog {

    private const val MAX_LINES = 300

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /*
     * SimpleDateFormat은 스레드 안전하지 않으므로
     * formatter.format()도 synchronized(lock) 내부에서 호출합니다.
     */
    private val formatter =
        SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        )

    fun i(
        tag: String,
        message: String
    ) {
        Log.i(tag, message)

        append(
            level = "I",
            tag = tag,
            message = message
        )
    }

    fun i(
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        Log.i(
            tag,
            message,
            throwable
        )

        append(
            level = "I",
            tag = tag,
            message = buildThrowableMessage(
                message,
                throwable
            )
        )
    }

    fun w(
        tag: String,
        message: String
    ) {
        Log.w(tag, message)

        append(
            level = "W",
            tag = tag,
            message = message
        )
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        Log.w(
            tag,
            message,
            throwable
        )

        append(
            level = "W",
            tag = tag,
            message = buildThrowableMessage(
                message,
                throwable
            )
        )
    }

    fun e(
        tag: String,
        message: String
    ) {
        Log.e(tag, message)

        append(
            level = "E",
            tag = tag,
            message = message
        )
    }

    /**
     * 예외 객체를 함께 기록하는 오버로드입니다.
     *
     * 사용 예:
     *
     * AgentLog.e(
     *     TAG,
     *     "handleClient failed",
     *     exception
     * )
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable
    ) {
        Log.e(
            tag,
            message,
            throwable
        )

        append(
            level = "E",
            tag = tag,
            message = buildThrowableMessage(
                message,
                throwable
            )
        )
    }

    fun dumpJson(): String {
        val array = JSONArray()

        synchronized(lock) {
            for (line in lines) {
                array.put(line)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("count", array.length())
            .put("logs", array)
            .toString()
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }

        Log.i(
            "MQ-AgentLog",
            "In-memory logs cleared"
        )
    }

    private fun append(
        level: String,
        tag: String,
        message: String
    ) {
        synchronized(lock) {
            val time =
                formatter.format(Date())

            val safeTag =
                tag.replace(
                    '\n',
                    ' '
                )

            val safeMessage =
                message
                    .replace(
                        '\r',
                        ' '
                    )
                    .replace(
                        '\n',
                        ' '
                    )
                    .take(4_000)

            val line =
                "$time $level/$safeTag $safeMessage"

            lines.addLast(line)

            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    /**
     * 메모리 로그가 지나치게 커지지 않도록
     * 예외 종류와 메시지만 저장합니다.
     *
     * 전체 스택 트레이스는 Android Logcat에 기록됩니다.
     */
    private fun buildThrowableMessage(
        message: String,
        throwable: Throwable
    ): String {
        val exceptionName =
            throwable.javaClass.simpleName
                .ifBlank {
                    throwable.javaClass.name
                }

        val exceptionMessage =
            throwable.message
                ?.trim()
                .orEmpty()

        return buildString {
            append(message)
            append(" | ")
            append(exceptionName)

            if (exceptionMessage.isNotBlank()) {
                append(": ")
                append(exceptionMessage)
            }

            val cause =
                throwable.cause

            if (
                cause != null &&
                cause !== throwable
            ) {
                append(" | cause=")
                append(
                    cause.javaClass.simpleName
                        .ifBlank {
                            cause.javaClass.name
                        }
                )

                val causeMessage =
                    cause.message
                        ?.trim()
                        .orEmpty()

                if (causeMessage.isNotBlank()) {
                    append(": ")
                    append(causeMessage)
                }
            }
        }
    }
}