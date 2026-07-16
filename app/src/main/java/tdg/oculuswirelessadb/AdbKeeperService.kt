package tdg.oculuswirelessadb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope

class AdbKeeperService : Service() {

    companion object {
        const val ACTION_START = "tdg.oculuswirelessadb.action.START"

        private const val CHANNEL_ID = "adb_keeper_channel"
        private const val CHANNEL_NAME = "ADB Maintenance"
        private const val NOTIFICATION_ID = 8881
        private const val TAG = "OW-AdbKeeper"

        private const val WATCHDOG_INTERVAL_MS = 8_000L
        private const val FIRST_DELAY_MS = 1_500L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var watchdogJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val adbDiscovery by lazy {
        JmDNSAdbDiscovery(applicationContext)
    }

    private var lastKnownIp: String? = null
    private var lastKnownPort: Int? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        createNotificationChannel()
        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}, startId=$startId")

        try {
            startAsForeground("무선 ADB 자동 유지 준비 중...")
            Log.i(TAG, "startForeground success")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startWatchdogIfNeeded()
        return START_STICKY
    }

    private fun startWatchdogIfNeeded() {
        if (watchdogJob?.isActive == true) {
            Log.i(TAG, "watchdog already running")
            return
        }

        watchdogJob = serviceScope.launch {
            delay(FIRST_DELAY_MS)

            while (isActive) {
                enforceAdbEnabledAndUpdateStatus()
                delay(WATCHDOG_INTERVAL_MS)
            }
        }

        Log.i(TAG, "watchdog started")
    }

    private suspend fun enforceAdbEnabledAndUpdateStatus() {
        try {
            val isEnabled = Settings.Global.getInt(
                contentResolver,
                "adb_wifi_enabled",
                0
            ) == 1

            if (!isEnabled) {
                Settings.Global.putInt(
                    contentResolver,
                    "adb_wifi_enabled",
                    1
                )

                Log.i(TAG, "adb_wifi_enabled forced ON")
            } else {
                Log.d(TAG, "adb_wifi_enabled already ON")
            }

            updateStatusNotification()
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS permission missing", e)
            updateNotification("권한 없음: WRITE_SECURE_SETTINGS 필요")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce wireless ADB", e)
            updateNotification("ADB 유지 오류: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun updateStatusNotification() {
        try {
            val isAdbOnInSettings = Settings.Global.getInt(
                contentResolver,
                "adb_wifi_enabled",
                0
            ) == 1

            if (!isAdbOnInSettings) {
                updateNotification("상태: OFF | 권한 부여 필요")
                return
            }

            val result = withTimeoutOrNull(4_500L) {
                adbDiscovery.discoverLocalAdbService(timeoutMs = 4_000L)
            }

            if (result != null) {
                lastKnownIp = result.first
                lastKnownPort = result.second

                updateNotification("연결 가능: $lastKnownIp:$lastKnownPort")
                Log.i(TAG, "ADB mDNS found: $lastKnownIp:$lastKnownPort")
            } else {
                if (lastKnownIp != null && lastKnownPort != null) {
                    updateNotification("연결 가능: $lastKnownIp:$lastKnownPort (신호 대기 중)")
                    Log.w(TAG, "mDNS not found, using last known endpoint")
                } else {
                    updateNotification("상태: ON | mDNS 최초 탐색 중...")
                    Log.w(TAG, "ADB is ON but mDNS not found yet")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateStatusNotification failed", e)
            updateNotification("상태 확인 오류: ${e.javaClass.simpleName}")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifiManager.createMulticastLock("AdbKeeperMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }

            Log.i(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        } finally {
            multicastLock = null
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(text)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntentFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Oculus Wireless ADB")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps wireless ADB enabled and monitors mDNS status"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "onDestroy")

        try {
            adbDiscovery.stopDiscovery()
        } catch (_: Exception) {
        }

        releaseMulticastLock()

        watchdogJob?.cancel()
        serviceScope.cancel()

        super.onDestroy()
    }
}