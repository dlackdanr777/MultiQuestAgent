package tdg.oculuswirelessadb.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Semaphore

class SimpleStatusHttpServer(
    context: Context,
    private val rtspPort: Int,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "MQ-HTTP"
        private const val FIXED_STATUS_PORT = 18080
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val BIND_RETRY_COUNT = 5
        private const val BIND_RETRY_DELAY_MS = 250L
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_CONCURRENT_CLIENTS = 24
    }

    private val appContext = context.applicationContext
    private val clientPermits = Semaphore(MAX_CONCURRENT_CLIENTS)

    @Volatile
    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    fun start(): Int {
        synchronized(this) {
            if (serverSocket?.isClosed == false) {
                return port
            }

            var lastError: Exception? = null
            var socket: ServerSocket? = null

            for (attempt in 0 until BIND_RETRY_COUNT) {
                try {
                    socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", FIXED_STATUS_PORT))
                    }
                    break
                } catch (e: Exception) {
                    lastError = e
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                    socket = null

                    if (attempt < BIND_RETRY_COUNT - 1) {
                        SystemClock.sleep(BIND_RETRY_DELAY_MS)
                    }
                }
            }

            val boundSocket = socket ?: throw IllegalStateException(
                "Could not bind fixed HTTP port $FIXED_STATUS_PORT after $BIND_RETRY_COUNT attempts",
                lastError
            )

            serverSocket = boundSocket
            port = boundSocket.localPort

            scope.launch(Dispatchers.IO) {
                acceptLoop(boundSocket)
            }

            Log.i(TAG, "HTTP server started on fixed port=$port")
            AgentLog.i(TAG, "HTTP server started on fixed port=$port")

            return port
        }
    }

    fun stop() {
        synchronized(this) {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }

            serverSocket = null
            port = 0
        }

        Log.i(TAG, "HTTP server stopped")
        AgentLog.i(TAG, "HTTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            try {
                val client = socket.accept()

                if (!clientPermits.tryAcquire()) {
                    try {
                        writeJsonResponse(
                            socket = client,
                            statusCode = 503,
                            json = jsonError(
                                code = "server_busy",
                                message = "too many concurrent requests"
                            )
                        )
                    } catch (_: Exception) {
                    } finally {
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }

                scope.launch(Dispatchers.IO) {
                    try {
                        handleClient(client)
                    } finally {
                        clientPermits.release()
                    }
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
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true

                val input = BufferedInputStream(socket.getInputStream())
                val headerBytes = readHeaderBlock(input)

                if (headerBytes.isEmpty()) {
                    writeJsonResponse(
                        socket,
                        400,
                        jsonError("bad_request", "empty request")
                    )
                    return
                }

                val headerText = String(
                    headerBytes,
                    StandardCharsets.ISO_8859_1
                )

                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val requestParts = requestLine.split(' ')

                if (requestParts.size < 2) {
                    writeJsonResponse(
                        socket,
                        400,
                        jsonError("bad_request", "invalid request line")
                    )
                    return
                }

                val method = requestParts[0].uppercase(Locale.US)
                val rawPath = requestParts[1]
                val path = rawPath.substringBefore('?')
                val headers = parseHeaders(lines.drop(1))

                val contentLength = headers["content-length"]
                    ?.toIntOrNull()
                    ?: 0

                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    writeJsonResponse(
                        socket,
                        413,
                        jsonError(
                            "payload_too_large",
                            "maximum body is $MAX_BODY_BYTES bytes"
                        )
                    )
                    return
                }

                val bodyBytes = readExact(input, contentLength)
                if (bodyBytes.size != contentLength) {
                    writeJsonResponse(
                        socket,
                        400,
                        jsonError("bad_request", "incomplete request body")
                    )
                    return
                }

                val body = String(bodyBytes, StandardCharsets.UTF_8)

                if (path.startsWith("/command/")) {
                    Log.i(TAG, "HTTP command: $method $path bodyBytes=${bodyBytes.size}")
                    AgentLog.i(TAG, "HTTP command: $method $path")
                }

                routeRequest(
                    socket = socket,
                    method = method,
                    path = path,
                    body = body
                )
            } catch (e: HeaderTooLargeException) {
                try {
                    writeJsonResponse(
                        socket,
                        431,
                        jsonError("headers_too_large", e.message ?: "headers too large")
                    )
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleClient failed", e)
                AgentLog.e(TAG, "handleClient failed", e)

                try {
                    writeJsonResponse(
                        socket,
                        500,
                        jsonError(
                            code = "server_error",
                            message = e.message ?: e.javaClass.simpleName
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun routeRequest(
        socket: Socket,
        method: String,
        path: String,
        body: String
    ) {
        when {
            method == "OPTIONS" -> {
                writeResponse(socket, 204, "text/plain", "")
            }

            method == "HEAD" && (path == "/" || path == "/status") -> {
                writeResponse(
                    socket = socket,
                    statusCode = 200,
                    contentType = "application/json",
                    body = "",
                    writeBody = false
                )
            }

            method == "GET" && (path == "/" || path == "/status") -> {
                writeJsonResponse(
                    socket = socket,
                    statusCode = 200,
                    json = AgentStatus.statusJson(
                        context = appContext,
                        statusPort = port,
                        rtspPort = rtspPort
                    )
                )
            }

            method == "GET" && path == "/health" -> {
                writeTextResponse(socket, 200, "ok")
            }

            method == "GET" && path == "/logs" -> {
                writeJsonResponse(socket, 200, AgentLog.dumpJson())
            }

            method == "GET" && path == "/operations/latest" -> {
                writeJsonResponse(
                    socket,
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("operation", AgentOperationStore.latestJson())
                        .toString()
                )
            }

            method == "GET" && path.startsWith("/operations/") -> {
                val operationId = path.removePrefix("/operations/").trim()
                val operation = AgentOperationStore.getJson(operationId)

                if (operation == null) {
                    writeJsonResponse(
                        socket,
                        404,
                        jsonError("operation_not_found", operationId)
                    )
                } else {
                    writeJsonResponse(
                        socket,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("operation", operation)
                            .toString()
                    )
                }
            }

            method == "POST" && path.startsWith("/command/") -> {
                val result = AgentCommandHandler.handle(
                    context = appContext,
                    path = path,
                    body = body
                )

                writeJsonResponse(
                    socket = socket,
                    statusCode = result.httpCode,
                    json = result.body.toString()
                )
            }

            else -> {
                writeJsonResponse(
                    socket,
                    404,
                    jsonError("not_found", "unknown route: $method $path")
                )
            }
        }
    }

    private fun readHeaderBlock(input: BufferedInputStream): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        var matched = 0

        while (result.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break

            result.write(value)

            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }

            if (matched == 4) {
                return result.toByteArray()
            }
        }

        if (result.size() >= MAX_HEADER_BYTES) {
            throw HeaderTooLargeException("request headers exceed $MAX_HEADER_BYTES bytes")
        }

        return result.toByteArray()
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()

        for (line in lines) {
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue

            headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                line.substring(idx + 1).trim()
        }

        return headers
    }

    private fun readExact(
        input: BufferedInputStream,
        count: Int
    ): ByteArray {
        if (count <= 0) return ByteArray(0)

        val result = ByteArray(count)
        var offset = 0

        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read <= 0) break
            offset += read
        }

        return if (offset == count) result else result.copyOf(offset)
    }

    private fun writeJsonResponse(
        socket: Socket,
        statusCode: Int,
        json: String
    ) {
        writeResponse(socket, statusCode, "application/json", json)
    }

    private fun writeTextResponse(
        socket: Socket,
        statusCode: Int,
        text: String
    ) {
        writeResponse(socket, statusCode, "text/plain", text)
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        contentType: String,
        body: String,
        writeBody: Boolean = true
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $statusCode ${statusText(statusCode)}\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${if (writeBody) bodyBytes.size else 0}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        output.write(headers)
        if (writeBody && bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
        }
        output.flush()
    }

    private fun statusText(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private fun jsonError(
        code: String,
        message: String
    ): String {
        return JSONObject()
            .put("ok", false)
            .put("accepted", false)
            .put("completed", false)
            .put("error", code)
            .put("message", message)
            .toString()
    }

    private class HeaderTooLargeException(message: String) : Exception(message)
}