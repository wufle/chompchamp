package au.chompchamp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps GPS fixes coming to [MainActivity] while a trip is recording and the screen is off or another
 * app is in front. Does no work itself; the notification shows it's on. Stopping a trip asks the user to
 * confirm, so it's done in the app: tapping the notification opens it.
 */
class TrackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_my_location)
            .setContentTitle("Recording a trip")
            .setContentText("Tap to open ChompChamp. To stop, press and hold REC on the map.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 1, n,
                if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "trip"

        /** Call while the app is on screen: Android doesn't allow starting it from the background. */
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TrackService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TrackService::class.java)) }
    }
}
