package tdg.oculuswirelessadb.rtsp

import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random
import tdg.oculuswirelessadb.capture.CaptureStateStore

class RtspH264TcpServer(private val port: Int) {

    companion object {
        private const val TAG = "MQ-RTSP"

        private const val RTP_PAYLOAD_TYPE = 96
        private const val RTP_CLOCK = 90000
        private const val RTP_CHANNEL = 0
        private const val RTCP_CHANNEL = 1

        private const val MAX_SINGLE_NAL_SIZE = 1200
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<RtspClient>()

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    @Volatile
    private var sps: ByteArray? = null

    @Volatile
    private var pps: ByteArray? = null

    @Volatile
    private var started = false

    @Volatile
    private var rtpStartNs: Long = 0L

    @Volatile
    private var rtpTimestampBase: Long = 0L

    fun start() {
        stop()

        try {
            val socket = ServerSocket(port)
            serverSocket = socket
            started = true

            rtpStartNs = System.nanoTime()
            rtpTimestampBase = Random.nextInt().toLong() and 0xFFFF_FFFFL

            CaptureStateStore.rtspClientCount = 0

            thread(name = "mq-rtsp-accept", isDaemon = true) {
                Log.i(
                    TAG,
                    "RTSP server started on port=$port " +
                            "rtpBase=$rtpTimestampBase"
                )

                while (started && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()

                        Log.i(
                            TAG,
                            "New TCP socket connected: ${clientSocket.inetAddress.hostAddress}"
                        )

                        val client = RtspClient(clientSocket)
                        clients.add(client)
                        updateClientCount()

                        thread(name = "mq-rtsp-client", isDaemon = true) {
                            try {
                                client.run()
                            } catch (e: Exception) {
                                Log.w(TAG, "Client thread ended: ${e.message}")
                            } finally {
                                clients.remove(client)
                                client.close()
                                updateClientCount()
                            }
                        }
                    } catch (e: Exception) {
                        if (started) {
                            Log.w(TAG, "Accept failed: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start RTSP server on port=$port", e)
        }
    }

    fun stop() {
        started = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        clients.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {
            }
        }

        clients.clear()
        updateClientCount()

        rtpStartNs = 0L
        rtpTimestampBase = 0L

        Log.i(TAG, "RTSP server stopped")
    }

    private fun updateClientCount() {
        val playingCount = clients.count { it.isPlaying }
        CaptureStateStore.rtspClientCount = playingCount

        Log.i(
            TAG,
            "RTSP clients: total=${clients.size}, playing=$playingCount"
        )
    }

    private fun currentRtpTimestamp90k(): Long {
        val startNs = rtpStartNs
        val base = rtpTimestampBase

        if (startNs <= 0L) {
            return base and 0xFFFF_FFFFL
        }

        val elapsedNs = System.nanoTime() - startNs
        val elapsed90k = (elapsedNs * RTP_CLOCK) / 1_000_000_000L

        return (base + elapsed90k) and 0xFFFF_FFFFL
    }

    fun setFormat(format: MediaFormat) {
        try {
            var newSps: ByteArray? = null
            var newPps: ByteArray? = null

            format.getByteBuffer("csd-0")?.let { buffer ->
                val bytes = copyBuffer(buffer)
                val nals = splitAccessUnit(bytes)

                newSps = nals.firstOrNull { H264Util.nalType(it) == 7 }
                newPps = nals.firstOrNull { H264Util.nalType(it) == 8 }
            }

            format.getByteBuffer("csd-1")?.let { buffer ->
                val bytes = copyBuffer(buffer)
                val nals = splitAccessUnit(bytes)

                newPps = nals.firstOrNull { H264Util.nalType(it) == 8 }
                    ?: extractNalByType(bytes, 8)
                            ?: newPps
            }

            if (newSps != null) sps = newSps
            if (newPps != null) pps = newPps

            Log.i(TAG, "Format updated. SPS=${sps?.size ?: 0} PPS=${pps?.size ?: 0}")
        } catch (e: Exception) {
            Log.e(TAG, "setFormat failed", e)
        }
    }

    fun setConfigData(configData: ByteArray) {
        if (configData.isEmpty()) return

        try {
            val nals = splitAccessUnit(configData)

            val foundSps = nals.firstOrNull { H264Util.nalType(it) == 7 }
            val foundPps = nals.firstOrNull { H264Util.nalType(it) == 8 }

            if (foundSps != null) sps = foundSps
            if (foundPps != null) pps = foundPps

            if (foundSps != null || foundPps != null) {
                Log.i(
                    TAG,
                    "Config data updated. SPS=${sps?.size ?: 0} PPS=${pps?.size ?: 0}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "setConfigData failed", e)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun pushAccessUnit(
        accessUnit: ByteArray,
        timestamp90k: Long,
        isKeyFrame: Boolean,
        inSps: ByteArray?,
        inPps: ByteArray?
    ) {
        if (accessUnit.isEmpty()) return

        if (inSps != null) sps = stripStartCodeIfNeeded(inSps)
        if (inPps != null) pps = stripStartCodeIfNeeded(inPps)

        val nals = splitAccessUnit(accessUnit)
        if (nals.isEmpty()) {
            Log.w(TAG, "No NAL units found. accessUnitSize=${accessUnit.size}")
            return
        }

        // 중요:
        // 외부 timestamp90k는 사용하지 않습니다.
        // MediaCodec presentationTimeUs나 currentTimeMillis 기반 값이 그대로 들어오면
        // RTP 90kHz timebase와 맞지 않아 VLC가 첫 프레임 이후 갱신하지 못할 수 있습니다.
        val rtpTimestamp = currentRtpTimestamp90k()

        clients.forEach { client ->
            if (client.isPlaying) {
                client.sendAccessUnit(nals, rtpTimestamp, isKeyFrame)
            }
        }
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        val out = ByteArray(duplicate.remaining())
        duplicate.get(out)
        return out
    }

    private fun extractNalByType(bytes: ByteArray, expectedType: Int): ByteArray? {
        if (bytes.isEmpty()) return null

        val nals = splitAccessUnit(bytes)
        val found = nals.firstOrNull { H264Util.nalType(it) == expectedType }
        if (found != null) return found

        val stripped = stripStartCodeIfNeeded(bytes)
        return if (H264Util.nalType(stripped) == expectedType) stripped else null
    }

    private fun splitAccessUnit(accessUnit: ByteArray): List<ByteArray> {
        if (accessUnit.isEmpty()) return emptyList()

        val annexB = H264Util.splitNalUnits(accessUnit)
        if (annexB.isNotEmpty()) return annexB

        val lengthPrefixed = splitLengthPrefixedNalUnits(accessUnit)
        if (lengthPrefixed.isNotEmpty()) return lengthPrefixed

        val stripped = stripStartCodeIfNeeded(accessUnit)
        val type = H264Util.nalType(stripped)

        return if (type in 1..31) {
            listOf(stripped)
        } else {
            emptyList()
        }
    }

    private fun splitLengthPrefixedNalUnits(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var pos = 0

        while (pos + 4 <= data.size) {
            val len =
                ((data[pos].toInt() and 0xFF) shl 24) or
                        ((data[pos + 1].toInt() and 0xFF) shl 16) or
                        ((data[pos + 2].toInt() and 0xFF) shl 8) or
                        (data[pos + 3].toInt() and 0xFF)

            if (len <= 0 || pos + 4 + len > data.size) {
                return emptyList()
            }

            out.add(data.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }

        return if (pos == data.size) out else emptyList()
    }

    private fun stripStartCodeIfNeeded(data: ByteArray): ByteArray {
        if (data.size >= 4 &&
            data[0].toInt() == 0 &&
            data[1].toInt() == 0 &&
            data[2].toInt() == 0 &&
            data[3].toInt() == 1
        ) {
            return data.copyOfRange(4, data.size)
        }

        if (data.size >= 3 &&
            data[0].toInt() == 0 &&
            data[1].toInt() == 0 &&
            data[2].toInt() == 1
        ) {
            return data.copyOfRange(3, data.size)
        }

        return data
    }

    private fun waitForParameterSets(timeoutMs: Long): Pair<ByteArray?, ByteArray?> {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val localSps = sps
            val localPps = pps

            if (localSps != null && localPps != null) {
                return localSps to localPps
            }

            try {
                Thread.sleep(20L)
            } catch (_: InterruptedException) {
                break
            }
        }

        return sps to pps
    }

    private inner class RtspClient(private val socket: Socket) {

        @Volatile
        var isPlaying: Boolean = false
            private set

        @Volatile
        private var closed = false

        @Volatile
        private var playNotified = false

        private val sessionId = Random.nextInt(100000, 999999).toString()

        private val input: InputStream = socket.getInputStream()
        private val output = socket.getOutputStream()

        private var sequence = Random.nextInt(0, 65535)
        private val ssrc = Random.nextInt()

        fun run() {
            while (!closed && !socket.isClosed) {
                val requestLine = readRtspLineSkippingInterleaved() ?: break

                if (requestLine.isBlank()) {
                    continue
                }

                if (!requestLine.startsWith("RTSP", ignoreCase = true) &&
                    !requestLine.contains("RTSP/1.0", ignoreCase = true)
                ) {
                    Log.w(TAG, "Ignoring malformed RTSP line: $requestLine")
                    continue
                }

                val headers = mutableMapOf<String, String>()

                while (!closed && !socket.isClosed) {
                    val line = readRtspLineSkippingInterleaved() ?: break

                    if (line.isBlank()) {
                        break
                    }

                    val idx = line.indexOf(":")
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[key] = value
                    }
                }

                val method = requestLine.substringBefore(" ").trim().uppercase()
                val cseq = headers["cseq"] ?: "1"

                when (method) {
                    "OPTIONS" -> {
                        respond(
                            cseq = cseq,
                            code = 200,
                            headers = mapOf(
                                "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"
                            )
                        )
                    }

                    "DESCRIBE" -> {
                        handleDescribe(cseq)
                    }

                    "SETUP" -> {
                        handleSetup(cseq)
                    }

                    "PLAY" -> {
                        handlePlay(cseq)
                    }

                    "GET_PARAMETER" -> {
                        handleGetParameter(cseq)
                    }

                    "TEARDOWN" -> {
                        isPlaying = false
                        updateClientCount()

                        respond(
                            cseq = cseq,
                            code = 200,
                            headers = mapOf("Session" to sessionId)
                        )

                        close()
                        break
                    }

                    else -> {
                        respond(
                            cseq = cseq,
                            code = 405,
                            headers = mapOf(
                                "Allow" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"
                            )
                        )
                    }
                }
            }

            close()
        }

        private fun readRtspLineSkippingInterleaved(): String? {
            while (!closed && !socket.isClosed) {
                val first = try {
                    input.read()
                } catch (_: Exception) {
                    return null
                }

                if (first < 0) {
                    return null
                }

                if (first == 0x24) {
                    if (!skipInterleavedFrame()) {
                        return null
                    }
                    continue
                }

                val buffer = ByteArrayOutputStream()

                if (first != '\r'.code && first != '\n'.code) {
                    buffer.write(first)
                }

                while (!closed && !socket.isClosed) {
                    val b = try {
                        input.read()
                    } catch (_: Exception) {
                        return null
                    }

                    if (b < 0) {
                        return null
                    }

                    if (b == '\n'.code) {
                        break
                    }

                    if (b != '\r'.code) {
                        buffer.write(b)
                    }
                }

                return buffer.toString(Charsets.UTF_8.name())
            }

            return null
        }

        private fun skipInterleavedFrame(): Boolean {
            return try {
                val channel = input.read()
                val hi = input.read()
                val lo = input.read()

                if (channel < 0 || hi < 0 || lo < 0) {
                    false
                } else {
                    val length = ((hi and 0xFF) shl 8) or (lo and 0xFF)
                    skipFully(length)

                    Log.d(TAG, "Skipped client interleaved frame. channel=$channel length=$length")
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun skipFully(length: Int) {
            var remaining = length
            val buffer = ByteArray(2048)

            while (remaining > 0 && !closed && !socket.isClosed) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) {
                    break
                }

                remaining -= read
            }
        }

        private fun handleDescribe(cseq: String) {
            val pair = waitForParameterSets(timeoutMs = 1500L)
            val localSps = pair.first
            val localPps = pair.second

            if (localSps == null || localPps == null) {
                Log.w(
                    TAG,
                    "DESCRIBE failed: SPS/PPS not ready. SPS=${localSps?.size ?: 0} PPS=${localPps?.size ?: 0}"
                )

                respond(
                    cseq = cseq,
                    code = 503,
                    headers = mapOf("Retry-After" to "1")
                )

                return
            }

            val sps64 = Base64.encodeToString(localSps, Base64.NO_WRAP)
            val pps64 = Base64.encodeToString(localPps, Base64.NO_WRAP)
            val profileLevelId = profileLevelIdFromSps(localSps)

            val sdp =
                "v=0\r\n" +
                        "o=- 0 0 IN IP4 0.0.0.0\r\n" +
                        "s=MultiQuest Streaming\r\n" +
                        "t=0 0\r\n" +
                        "a=control:*\r\n" +
                        "m=video 0 RTP/AVP $RTP_PAYLOAD_TYPE\r\n" +
                        "c=IN IP4 0.0.0.0\r\n" +
                        "a=rtpmap:$RTP_PAYLOAD_TYPE H264/$RTP_CLOCK\r\n" +
                        "a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$sps64,$pps64\r\n" +
                        "a=control:trackID=1\r\n\r\n"

            respond(
                cseq = cseq,
                code = 200,
                headers = mapOf("Content-Type" to "application/sdp"),
                body = sdp
            )
        }

        private fun handleSetup(cseq: String) {
            respond(
                cseq = cseq,
                code = 200,
                headers = mapOf(
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=$RTP_CHANNEL-$RTCP_CHANNEL;ssrc=${ssrcHex()}",
                    "Session" to "$sessionId;timeout=60"
                )
            )
        }

        private fun handlePlay(cseq: String) {
            isPlaying = true
            updateClientCount()

            val timestamp = currentRtpTimestamp90k()
            val startSeq = sequence

            respond(
                cseq = cseq,
                code = 200,
                headers = mapOf(
                    "Session" to sessionId,
                    "Range" to "npt=0.000-",
                    "RTP-Info" to "url=trackID=1;seq=$startSeq;rtptime=$timestamp"
                )
            )

            if (!playNotified) {
                playNotified = true
                onClientConnected?.invoke()
            }

            sps?.let { sendNal(it, timestamp, marker = false) }
            pps?.let { sendNal(it, timestamp, marker = true) }

            Log.i(
                TAG,
                "PLAY accepted. session=$sessionId seq=$startSeq rtptime=$timestamp"
            )
        }

        private fun handleGetParameter(cseq: String) {
            respond(
                cseq = cseq,
                code = 200,
                headers = mapOf("Session" to sessionId)
            )
        }

        fun sendAccessUnit(
            nals: List<ByteArray>,
            timestamp: Long,
            isKeyFrame: Boolean
        ) {
            if (closed || !isPlaying) return

            if (isKeyFrame) {
                sps?.let { sendNal(it, timestamp, marker = false) }
                pps?.let { sendNal(it, timestamp, marker = false) }
            }

            for (i in nals.indices) {
                val nal = nals[i]
                val isLast = i == nals.lastIndex
                sendNal(nal, timestamp, marker = isLast)
            }
        }

        private fun sendNal(nal: ByteArray, timestamp: Long, marker: Boolean) {
            if (nal.isEmpty()) return

            if (nal.size <= MAX_SINGLE_NAL_SIZE) {
                sendRtpPacket(nal, timestamp, marker)
            } else {
                sendFuA(nal, timestamp, marker)
            }
        }

        private fun sendRtpPacket(payload: ByteArray, timestamp: Long, marker: Boolean) {
            val rtp = ByteArray(12 + payload.size)

            val ts = timestamp and 0xFFFF_FFFFL

            rtp[0] = 0x80.toByte()
            rtp[1] = (RTP_PAYLOAD_TYPE or if (marker) 0x80 else 0x00).toByte()

            rtp[2] = (sequence shr 8).toByte()
            rtp[3] = (sequence and 0xFF).toByte()

            rtp[4] = (ts shr 24).toByte()
            rtp[5] = (ts shr 16).toByte()
            rtp[6] = (ts shr 8).toByte()
            rtp[7] = (ts and 0xFF).toByte()

            rtp[8] = (ssrc shr 24).toByte()
            rtp[9] = (ssrc shr 16).toByte()
            rtp[10] = (ssrc shr 8).toByte()
            rtp[11] = (ssrc and 0xFF).toByte()

            System.arraycopy(payload, 0, rtp, 12, payload.size)

            sendInterleaved(RTP_CHANNEL, rtp)

            sequence = (sequence + 1) and 0xFFFF
        }

        private fun sendFuA(nal: ByteArray, timestamp: Long, marker: Boolean) {
            if (nal.isEmpty()) return

            val nalHeader = nal[0].toInt() and 0xFF
            val nri = nalHeader and 0x60
            val type = nalHeader and 0x1F

            var pos = 1

            while (pos < nal.size) {
                val len = minOf(nal.size - pos, MAX_SINGLE_NAL_SIZE - 2)
                val isStart = pos == 1
                val isLast = pos + len == nal.size

                val fu = ByteArray(len + 2)

                fu[0] = (nri or 28).toByte()
                fu[1] = (
                        (if (isStart) 0x80 else 0x00) or
                                (if (isLast) 0x40 else 0x00) or
                                type
                        ).toByte()

                System.arraycopy(nal, pos, fu, 2, len)

                sendRtpPacket(fu, timestamp, marker && isLast)

                pos += len
            }
        }

        @Synchronized
        private fun sendInterleaved(channel: Int, data: ByteArray) {
            if (closed) return

            try {
                output.write(0x24)
                output.write(channel)
                output.write(data.size shr 8)
                output.write(data.size and 0xFF)
                output.write(data)
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "sendInterleaved failed. session=$sessionId error=${e.message}")
                close()
            }
        }

        private fun respond(
            cseq: String,
            code: Int,
            headers: Map<String, String>,
            body: String = ""
        ) {
            val reason = when (code) {
                200 -> "OK"
                405 -> "Method Not Allowed"
                503 -> "Service Unavailable"
                else -> "OK"
            }

            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            val headerBlock = if (headers.isNotEmpty()) {
                headers.map { "${it.key}: ${it.value}" }.joinToString("\r\n") + "\r\n"
            } else {
                ""
            }

            val response =
                "RTSP/1.0 $code $reason\r\n" +
                        "CSeq: $cseq\r\n" +
                        headerBlock +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "\r\n"

            try {
                output.write(response.toByteArray(Charsets.UTF_8))

                if (bodyBytes.isNotEmpty()) {
                    output.write(bodyBytes)
                }

                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "respond failed. session=$sessionId error=${e.message}")
                close()
            }
        }

        private fun ssrcHex(): String {
            return String.format("%08x", ssrc)
        }

        @Synchronized
        fun close() {
            if (closed) {
                return
            }

            val shouldNotifyDisconnect = playNotified

            playNotified = false
            isPlaying = false
            closed = true

            try {
                socket.close()
            } catch (_: Exception) {
            }

            updateClientCount()

            if (shouldNotifyDisconnect) {
                onClientDisconnected?.invoke()
            }

            Log.i(TAG, "RTSP client closed. session=$sessionId")
        }
    }

    private fun profileLevelIdFromSps(sps: ByteArray): String {
        return if (sps.size >= 4) {
            String.format(
                "%02x%02x%02x",
                sps[1].toInt() and 0xFF,
                sps[2].toInt() and 0xFF,
                sps[3].toInt() and 0xFF
            )
        } else {
            "42e01f"
        }
    }
}