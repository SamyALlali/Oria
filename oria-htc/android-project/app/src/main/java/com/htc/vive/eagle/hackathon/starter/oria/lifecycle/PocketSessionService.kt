package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.htc.vive.eagle.hackathon.starter.MainActivity
import com.htc.vive.eagle.hackathon.starter.R
import com.htc.vive.eagle.hackathon.starter.oria.OriaController

/** Explicit, bounded foreground experiment. An Activity destruction ends the session.
 * Never sticky: a dead process or an old notification cannot restart camera or speech.
 * All ownership transitions are serialized on the main thread, like OriaController.
 */
class PocketSessionService : Service() {
    private var owner: OriaController? = null
    private var ownerToken: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish("Mode poche : limite de 15 minutes atteinte") }
    private var receiverRegistered = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (value == BluetoothAdapter.STATE_OFF || value == BluetoothAdapter.STATE_TURNING_OFF) {
                finish("Bluetooth désactivé · session arrêtée")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getLongExtra("token", -1L)
        if (intent?.action == ACTION_STOP) {
            // An older notification must not stop a subsequent session.
            if (token == ownerToken) finish("Session arrêtée depuis la notification")
            else if (owner == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val pending = requested
        if (pending == null || pending.token != token || !pending.visible() ||
            !pending.controller.state.value.pocketEnabled || pending.controller.state.value.running) {
            if (pending != null && pending.token == token) {
                requested = null
                pending.controller.reportPocketError("Mode poche annulé · revenir dans Oria pour démarrer")
            }
            if (owner == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        requested = null
        owner = pending.controller
        ownerToken = pending.token
        active = this
        try {
            require(Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "Autorisez les appareils Bluetooth à proximité"
            }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Oria · session en cours",
                NotificationManager.IMPORTANCE_LOW))
            check(notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
                "Autorisez les notifications Oria pour garder le bouton Arrêter accessible"
            }
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, pending.token.toInt(),
                Intent(this, PocketSessionService::class.java).setAction(ACTION_STOP).putExtra("token", pending.token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.glasses_solid_full)
                .setContentTitle("Oria · mode poche expérimental")
                .setContentText("Analyse des lunettes active · essai limité à 15 min")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Arrêter", stop).build()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Oria:PocketSession").apply {
                setReferenceCounted(false)
                acquire(PocketSessionPolicy.MAX_DURATION_MS)
            }
            ContextCompat.registerReceiver(this, bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            startedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(timeout, PocketSessionPolicy.MAX_DURATION_MS)
            pending.controller.pocketServiceReady()
            pending.controller.start()
            if (!pending.controller.state.value.running) finish("Mode poche : démarrage refusé")
        } catch (error: Exception) {
            finish("Mode poche indisponible : ${error.message ?: error.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun finish(reason: String) {
        val controller = owner
        owner = null
        ownerToken = null
        if (active === this) active = null
        controller?.stop(reason)
        controller?.reportPocketError(reason)
        releaseResources()
        stopSelf()
    }

    private fun releaseResources() {
        handler.removeCallbacks(timeout)
        if (receiverRegistered) { unregisterReceiver(bluetoothReceiver); receiverRegistered = false }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        finish("Application fermée · session arrêtée")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        finish("Service arrêté · session arrêtée")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "oria_pocket_session"
        private const val NOTIFICATION_ID = 701
        private const val ACTION_START = "oria.POCKET_START"
        private const val ACTION_STOP = "oria.POCKET_STOP"
        private data class Request(val token: Long, val controller: OriaController, val visible: () -> Boolean)
        private var requested: Request? = null
        private var active: PocketSessionService? = null
        private var sequence = 0L
        private var startedAt = 0L

        fun request(context: Context, controller: OriaController, visible: () -> Boolean) {
            if (requested != null || active != null || !visible()) return
            val state = controller.state.value
            if (!state.pocketEnabled || state.running || !state.connected || !state.modelReady || state.simulator) {
                controller.reportPocketError("Mode poche : connecter les lunettes réelles et attendre le modèle")
                return
            }
            val request = Request(++sequence, controller, visible)
            requested = request
            controller.pocketServicePreparing()
            try {
                ContextCompat.startForegroundService(context, Intent(context, PocketSessionService::class.java)
                    .setAction(ACTION_START).putExtra("token", request.token))
            } catch (error: Exception) {
                if (requested === request) requested = null
                controller.reportPocketError("Mode poche indisponible : ${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun isReadyFor(controller: OriaController): Boolean = active?.owner === controller &&
            SystemClock.elapsedRealtime() - startedAt in 0 until PocketSessionPolicy.MAX_DURATION_MS

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

        fun release(controller: OriaController, context: Context) {
            val pendingOwned = requested?.controller === controller
            if (pendingOwned) requested = null
            val service = active
            if (service?.owner === controller) {
                service.owner = null
                service.ownerToken = null
                active = null
                service.releaseResources()
                service.stopSelf()
            } else if (service == null && pendingOwned) {
                context.stopService(Intent(context, PocketSessionService::class.java))
            }
        }
    }
}
