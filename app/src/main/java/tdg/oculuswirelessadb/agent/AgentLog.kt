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
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        append("E", tag, message)
    }

    fun dumpJson(): String {
        val arr = JSONArray()

        synchronized(lock) {
            for (line in lines) {
                arr.put(line)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("logs", arr)
            .toString()
    }

    private fun append(
        level: String,
        tag: String,
        message: String
    ) {
        val time = formatter.format(Date())
        val line = "$time $level/$tag $message"

        synchronized(lock) {
            lines.addLast(line)

            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }
}