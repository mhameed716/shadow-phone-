package com.shadowphone.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Owns the Wi-Fi Direct/UDP controller independently of the Activity lifecycle. */
internal class MeshKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "shadow_phone_mesh"
        private const val NOTIFICATION_ID = 45871
        private const val ACTION_START = "com.shadowphone.app.action.START_MESH"
        private const val ACTION_STOP = "com.shadowphone.app.action.STOP_MESH"

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context, MeshKeepAliveService::class.java).setAction(ACTION_START)
            )
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            try { context.applicationContext.stopService(Intent(context, MeshKeepAliveService::class.java)) }
            catch (_: Exception) { }
        }
    }

    inner class LocalBinder : Binder() {
        fun controller(): WifiDirectMeshController? = meshController
    }

    private val localBinder = LocalBinder()
    @Volatile private var meshController: WifiDirectMeshController? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "اتصال Shadow Phone المحلي", NotificationManager.IMPORTANCE_LOW).apply {
                description = "إبقاء اتصال Wi-Fi Direct المحلي نشطاً أثناء تشغيل التطبيق"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, MeshKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Shadow Phone")
            .setContentText("Shadow Phone يعمل في الخلفية وجاهز للاستقبال")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "إيقاف",
                    stopService
                ).build()
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureMeshController()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        ensureMeshController()
        return localBinder
    }

    override fun onDestroy() {
        meshController?.close()
        meshController = null
        super.onDestroy()
    }

    private fun ensureMeshController() {
        if (meshController != null) return
        val controller = WifiDirectMeshController(applicationContext)
        val storedNumber = getSharedPreferences("shadow_phone", Context.MODE_PRIVATE).getString("phone_number", "").orEmpty()
        if (storedNumber.isNotBlank()) controller.setPhoneNumber(storedNumber)
        meshController = controller
        controller.register()
        if (hasDiscoveryPermission()) controller.startDiscovery()
    }

    private fun hasDiscoveryPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
