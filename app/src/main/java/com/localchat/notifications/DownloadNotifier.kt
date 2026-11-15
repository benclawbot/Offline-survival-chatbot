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

private const val CHANNEL_PROGRESS = "download_progress"
private const val CHANNEL_RESULT = "download_result"

@Singleton
class DownloadNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Прогресс скачивания модели" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT, "Результат загрузки", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Уведомления об окончании загрузки" }
        )
    }

    fun notifyId(modelId: String): Int = ("dl-$modelId").hashCode()

    fun progress(modelId: String, modelName: String, percent: Int, speed: String) {
        if (!canPost()) return
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle("Загрузка \"$modelName\"")
            .setContentText("$percent% · $speed")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setContentIntent(openIntent)
            .build()
        runCatching { nm.notify(notifyId(modelId), n) }
    }

    fun done(modelId: String, modelName: String) {
        if (!canPost()) return
        nm.cancel(notifyId(modelId))
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle("\"$modelName\" загружена")
            .setContentText("Можно загружать модель в чате")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notifyId("done-$modelId"), n) }
    }

    fun error(modelId: String, modelName: String, message: String) {
        if (!canPost()) return
        nm.cancel(notifyId(modelId))
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle("Ошибка загрузки \"$modelName\"")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notifyId("err-$modelId"), n) }
    }

    fun cancelled(modelId: String) {
        nm.cancel(notifyId(modelId))
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

