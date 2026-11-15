package com.localchat.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.localchat.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_RUNTIME = "model_runtime"
private const val NOTIFY_RUNTIME = 4242

@Singleton
class RuntimeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNTIME, "Загруженная модель", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Уведомление пока модель в памяти" }
        )
    }

    fun showLoaded(modelName: String, accelerator: String) {
        if (!canPost()) return
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val unloadIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, UnloadModelReceiver::class.java).setAction(UnloadModelReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_RUNTIME)
            .setContentTitle(modelName)
            .setContentText("Загружена в память · $accelerator")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выгрузить", unloadIntent)
            .build()
        runCatching { nm.notify(NOTIFY_RUNTIME, n) }
    }

    fun clear() {
        nm.cancel(NOTIFY_RUNTIME)
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

