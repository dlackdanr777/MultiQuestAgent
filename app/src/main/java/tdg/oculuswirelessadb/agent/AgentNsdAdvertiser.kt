package tdg.oculuswirelessadb.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class AgentNsdAdvertiser(context: Context) {

    companion object {
        private const val TAG = "MQ-NSD"

        // WPF 검색 타입: "_multiquest-agent._tcp.local."
        private const val SERVICE_TYPE = "_multiquest-agent._tcp."

        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
    }

    private enum class State {
        IDLE,
        REGISTERING,
        REGISTERED,
        UNREGISTERING
    }

    private data class RegistrationRequest(
        val statusPort: Int,
        val rtspPort: Int,
        val revision: Long
    )

    private val appContext = context.applicationContext

    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    // 상태 변경과 NSD 호출을 하나의 스레드에서 순서대로 처리합니다.
    private val handler = Handler(Looper.getMainLooper())

    private var state = State.IDLE

    // 최종적으로 원하는 등록 상태. null이면 광고 중지.
    private var desiredRequest: RegistrationRequest? = null

    // 현재 등록 중이거나 등록된 요청.
    private var activeRequest: RegistrationRequest? = null

    private var registrationListener:
            NsdManager.RegistrationListener? = null

    private var revision = 0L

    private var retryDelayMs = INITIAL_RETRY_MS
    private var retryRunnable: Runnable? = null

    /**
     * 광고 등록 또는 재등록을 요청합니다.
     *
     * 기존 광고가 있으면 해제 완료를 기다린 후 등록합니다.
     * 여러 요청이 들어오면 마지막 요청을 사용합니다.
     */
    fun register(statusPort: Int, rtspPort: Int) {
        require(statusPort in 1..65535) {
            "Invalid statusPort: $statusPort"
        }
        require(rtspPort in 1..65535) {
            "Invalid rtspPort: $rtspPort"
        }

        handler.post {
            revision++

            desiredRequest = RegistrationRequest(
                statusPort = statusPort,
                rtspPort = rtspPort,
                revision = revision
            )

            cancelRetry()
            retryDelayMs = INITIAL_RETRY_MS

            reconcile()
        }
    }

    /**
     * 광고를 중지합니다.
     *
     * 등록 진행 중이면 등록 결과를 받은 뒤 해제합니다.
     * 이후 register()를 호출하면 다시 광고할 수 있습니다.
     */
    fun unregister() {
        handler.post {
            desiredRequest = null

            cancelRetry()
            retryDelayMs = INITIAL_RETRY_MS

            reconcile()
        }
    }

    /**
     * 현재 상태를 마지막 요청에 맞춥니다.
     * 반드시 handler 스레드에서 호출합니다.
     */
    private fun reconcile() {
        if (retryRunnable != null) {
            return
        }

        when (state) {
            State.IDLE -> {
                val request = desiredRequest ?: return
                beginRegistration(request)
            }

            State.REGISTERED -> {
                val desired = desiredRequest
                val active = activeRequest

                if (desired == null || desired != active) {
                    beginUnregistration()
                }
            }

            State.REGISTERING,
            State.UNREGISTERING -> {
                // 작업 완료 콜백에서 마지막 요청을 다시 확인합니다.
            }
        }
    }

    private fun beginRegistration(request: RegistrationRequest) {
        val serviceInfo = try {
            val deviceId = AgentStatus.deviceId(appContext)

            check(deviceId.isNotBlank()) {
                "Device ID is blank; NSD registration deferred"
            }

            val model = Build.MODEL ?: "Quest"
            val shortId = deviceId.takeLast(6)

            NsdServiceInfo().apply {
                serviceType = SERVICE_TYPE
                serviceName =
                    "MQ-${model.replace(" ", "_")}-$shortId"
                port = request.statusPort

                setAttribute("id", deviceId)
                setAttribute("model", model)
                setAttribute("http", request.statusPort.toString())
                setAttribute("rtsp", request.rtspPort.toString())
                setAttribute("ver", "0.1.0")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare NSD registration", e)
            scheduleRetry()
            return
        }

        val listener = object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(info: NsdServiceInfo) {
                val source = this

                handler.post {
                    if (registrationListener !== source) {
                        return@post
                    }

                    state = State.REGISTERED
                    retryDelayMs = INITIAL_RETRY_MS

                    Log.i(
                        TAG,
                        "Registered: ${info.serviceName} " +
                                "${info.serviceType} port=${info.port}"
                    )

                    // 登録中に中止や再登録を要求されていれば反映。
                    reconcile()
                }
            }

            override fun onRegistrationFailed(
                info: NsdServiceInfo,
                errorCode: Int
            ) {
                val source = this

                handler.post {
                    if (registrationListener !== source) {
                        return@post
                    }

                    Log.e(
                        TAG,
                        "Registration failed: errorCode=$errorCode"
                    )

                    clearActiveRegistration()

                    if (desiredRequest != null) {
                        scheduleRetry()
                    }
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                val source = this

                handler.post {
                    if (registrationListener !== source) {
                        return@post
                    }

                    Log.i(TAG, "Unregistered: ${info.serviceName}")

                    clearActiveRegistration()
                    retryDelayMs = INITIAL_RETRY_MS

                    // 해제 완료 후에만 다음 등록을 시작합니다.
                    reconcile()
                }
            }

            override fun onUnregistrationFailed(
                info: NsdServiceInfo,
                errorCode: Int
            ) {
                val source = this

                handler.post {
                    if (registrationListener !== source) {
                        return@post
                    }

                    Log.e(
                        TAG,
                        "Unregistration failed: errorCode=$errorCode"
                    )

                    // 해제 완료가 확인되지 않았으므로
                    // 기존 리스너를 유지하고 해제를 다시 시도합니다.
                    state = State.REGISTERED
                    scheduleRetry()
                }
            }
        }

        activeRequest = request
        registrationListener = listener
        state = State.REGISTERING

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )

            Log.d(
                TAG,
                "Registration requested: " +
                        "port=${request.statusPort}, " +
                        "revision=${request.revision}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "registerService threw an exception", e)

            clearActiveRegistration()
            scheduleRetry()
        }
    }

    private fun beginUnregistration() {
        val listener = registrationListener

        if (listener == null) {
            clearActiveRegistration()
            reconcile()
            return
        }

        state = State.UNREGISTERING

        try {
            nsdManager.unregisterService(listener)
            Log.d(TAG, "Unregistration requested")
        } catch (e: IllegalArgumentException) {
            // 프레임워크에 이미 등록되지 않은 리스너인 경우.
            Log.w(TAG, "Listener is no longer registered", e)

            clearActiveRegistration()
            reconcile()
        } catch (e: Exception) {
            Log.e(TAG, "unregisterService threw an exception", e)

            // 해제 여부가 불확실하므로 새 광고를 중복 생성하지 않습니다.
            state = State.REGISTERED
            scheduleRetry()
        }
    }

    private fun clearActiveRegistration() {
        registrationListener = null
        activeRequest = null
        state = State.IDLE
    }

    private fun scheduleRetry() {
        if (retryRunnable != null) {
            return
        }

        // 등록 요청도 없고 해제할 광고도 없으면 재시도하지 않습니다.
        if (desiredRequest == null && state == State.IDLE) {
            return
        }

        val delayMs = retryDelayMs

        retryDelayMs =
            (retryDelayMs * 2L).coerceAtMost(MAX_RETRY_MS)

        val runnable = Runnable {
            retryRunnable = null
            reconcile()
        }

        retryRunnable = runnable
        handler.postDelayed(runnable, delayMs)

        Log.w(
            TAG,
            "Retry scheduled: delayMs=$delayMs, state=$state"
        )
    }

    private fun cancelRetry() {
        retryRunnable?.let {
            handler.removeCallbacks(it)
        }
        retryRunnable = null
    }
}