package com.cloudvault.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import java.net.InetAddress
import java.net.ServerSocket

/** Keeps the embedded Python server (server.py) alive, also when the app is in the background. */
class ServerService : Service() {

    companion object {
        const val ACTION_STOP = "com.cloudvault.app.STOP"
        private const val CHANNEL = "vault_server"

        @Volatile var port = 0
        @Volatile private var pythonStarted = false

        /** Same port every time (WebView keeps its saved settings per port). 8080 first, else a free random one. */
        @Synchronized
        fun ensurePort(ctx: Context): Int {
            if (port != 0) return port
            val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
            val saved = prefs.getInt("port", 0)
            val candidates = listOfNotNull(saved.takeIf { it > 0 }, 8080)
            var chosen = candidates.firstOrNull { isFree(it) } ?: ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            prefs.edit().putInt("port", chosen).apply()
            port = chosen
            return chosen
        }

        private fun isFree(p: Int) = try {
            ServerSocket(p, 0, InetAddress.getByName("127.0.0.1")).use { true }
        } catch (e: Exception) { false }

        fun dataDir(ctx: Context) = ctx.filesDir.absolutePath + "/vault"
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cloud Vault server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Cloud Vault")
            .setContentText("Running - uploads and syncs continue in the background")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf(); return
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudVault:server").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wake?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Python cannot be stopped cleanly inside the process, so the whole process ends.
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        startPython()
        return START_STICKY            // system restarts the service (and server) after the process was killed
    }

    @Synchronized
    private fun startPython() {
        if (pythonStarted) return
        val p = ensurePort(this)
        Thread {
            try {
                Python.getInstance().getModule("android_entry")
                    .callAttr("start", p, dataDir(this), cacheDir.absolutePath)
                pythonStarted = true
            } catch (e: Throwable) {
                android.util.Log.e("CloudVault", "python start failed", e)
            }
        }.start()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
