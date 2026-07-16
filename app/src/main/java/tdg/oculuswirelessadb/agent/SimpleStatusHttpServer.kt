package tdg.oculuswirelessadb.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

class SimpleStatusHttpServer(
    private val context: Context,
    private val rtspPort: Int,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "MQ-HTTP"
        private const val FIXED_STATUS_PORT = 18080
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true

        try {
            socket.bind(InetSocketAddress("0.0.0.0", FIXED_STATUS_PORT))
        } catch (e: Exception) {
            Log.w(
                TAG,
                "preferred port $FIXED_STATUS_PORT unavailable, fallback random port",
                e
            )
            AgentLog.w(
                TAG,
                "preferred port $FIXED_STATUS_PORT unavailable, fallback random port: ${e.message}"
            )

            socket.bind(InetSocketAddress("0.0.0.0", 0))
        }

        serverSocket = socket
        port = socket.localPort

        scope.launch(Dispatchers.IO) {
            acceptLoop(socket)
        }

        Log.i(TAG, "HTTP server started on port=$port")
        AgentLog.i(TAG, "HTTP server started on port=$port")

        return port
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        Log.i(TAG, "HTTP server stopped")
        AgentLog.i(TAG, "HTTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            try {
                val client = socket.accept()

                scope.launch(Dispatchers.IO) {
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    Log.w(TAG, "accept failed", e)
                    AgentLog.w(TAG, "accept failed: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = SOCKET_TIMEOUT_MS

                val reader = BufferedReader(
                    InputStreamReader(
                        sock.getInputStream(),
                        StandardCharsets.UTF_8
                    )
                )

                val requestLine = reader.readLine().orEmpty()

                if (requestLine.isBlank()) {
                    writeJsonResponse(
                        socket = sock,
                        statusCode = 400,
                        json = jsonError(
                            code = "bad_request",
                            message = "empty request line"
                        )
                    )
                    return
                }

                val requestParts = requestLine.split(" ")
                val method = requestParts
                    .getOrNull(0)
                    ?.uppercase(Locale.US)
                    ?: "GET"

                val rawPath = requestParts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore("?")

                val headers = readHeaders(reader)
                val body = readBody(reader, headers)

                Log.i(TAG, "HTTP request: $method $path body=${body.length}")
                AgentLog.i(TAG, "HTTP request: $method $path body=${body.length}")

                when {
                    method == "OPTIONS" -> {
                        writeResponse(
                            socket = sock,
                            statusCode = 200,
                            contentType = "text/plain",
                            body = ""
                        )
                    }

                    method == "HEAD" && (path == "/" || path == "/status") -> {
                        writeResponse(
                            socket = sock,
                            statusCode = 200,
                            contentType = "application/json",
                            body = "",
                            writeBody = false
                        )
                    }

                    method == "GET" && (path == "/" || path == "/status") -> {
                        val json = AgentStatus.statusJson(
                            context = context,
                            statusPort = port,
                            rtspPort = rtspPort
                        )

                        writeJsonResponse(
                            socket = sock,
                            statusCode = 200,
                            json = json
                        )
                    }

                    method == "GET" && path == "/health" -> {
                        writeTextResponse(
                            socket = sock,
                            statusCode = 200,
                            text = "ok"
                        )
                    }

                    method == "GET" && path == "/logs" -> {
                        writeJsonResponse(
                            socket = sock,
                            statusCode = 200,
                            json = AgentLog.dumpJson()
                        )
                    }

                    method == "POST" && path.startsWith("/command/") -> {
                        val result = AgentCommandHandler.handle(
                            context = context,
                            path = path,
                            body = body
                        )

                        writeJsonResponse(
                            socket = sock,
                            statusCode = result.httpCode,
                            json = result.body.toString()
                        )
                    }

                    else -> {
                        writeJsonResponse(
                            socket = sock,
                            statusCode = 404,
                            json = jsonError(
                                code = "not_found",
                                message = "unknown route: $method $path"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleClient failed", e)
                AgentLog.e(TAG, "handleClient failed: ${e.message}")

                try {
                    writeJsonResponse(
                        socket = sock,
                        statusCode = 500,
                        json = jsonError(
                            code = "server_error",
                            message = e.message ?: e.javaClass.simpleName
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = reader.readLine() ?: break

            if (line.isEmpty()) {
                break
            }

            val idx = line.indexOf(":")
            if (idx > 0) {
                val key = line.substring(0, idx)
                    .trim()
                    .lowercase(Locale.US)

                val value = line.substring(idx + 1)
                    .trim()

                headers[key] = value
            }
        }

        return headers
    }

    private fun readBody(
        reader: BufferedReader,
        headers: Map<String, String>
    ): String {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

        if (contentLength <= 0) {
            return ""
        }

        val chars = CharArray(contentLength)
        var readTotal = 0

        while (readTotal < contentLength) {
            val read = reader.read(
                chars,
                readTotal,
                contentLength - readTotal
            )

            if (read <= 0) {
                break
            }

            readTotal += read
        }

        return String(chars, 0, readTotal)
    }

    private fun writeJsonResponse(
        socket: Socket,
        statusCode: Int,
        json: String
    ) {
        writeResponse(
            socket = socket,
            statusCode = statusCode,
            contentType = "application/json",
            body = json
        )
    }

    private fun writeTextResponse(
        socket: Socket,
        statusCode: Int,
        text: String
    ) {
        writeResponse(
            socket = socket,
            statusCode = statusCode,
            contentType = "text/plain",
            body = text
        )
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        contentType: String,
        body: String,
        writeBody: Boolean = true
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }

        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = OutputStreamWriter(
            socket.getOutputStream(),
            StandardCharsets.UTF_8
        )

        writer.write("HTTP/1.1 $statusCode $statusText\r\n")
        writer.write("Content-Type: $contentType; charset=utf-8\r\n")
        writer.write("Content-Length: ${if (writeBody) bodyBytes.size else 0}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n")
        writer.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()

        if (writeBody && bodyBytes.isNotEmpty()) {
            socket.getOutputStream().write(bodyBytes)
            socket.getOutputStream().flush()
        }
    }

    private fun jsonError(
        code: String,
        message: String
    ): String {
        return JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message)
            .toString()
    }
}