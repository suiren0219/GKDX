package io.github.gkdhide.gkdx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicInteger

/** Root 模式的前台服务：保证引擎在后台存活，通知栏可随时停止 */
class RootEngineService : Service() {

    private val seq = AtomicInteger(0)
    private var lastStatus = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RootEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val error = RootEngine.start { msg ->
            lastStatus = msg
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(msg))
        }
        if (error != null) {
            lastStatus = error
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(error))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RootEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, "GKDX Root 模式", NotificationManager.IMPORTANCE_LOW,
        )
        ch.description = "Root 模式运行状态"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConfigActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RootEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GKDX Root 模式")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.gkdhide.gkdx.STOP_ROOT_ENGINE"
        private const val CHANNEL_ID = "gkdx_root"
        private const val NOTIFICATION_ID = 0x6b6478
    }
}
