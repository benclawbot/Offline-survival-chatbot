package com.localchat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.localchat.domain.repo.ModelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UnloadModelReceiver : BroadcastReceiver() {
    @Inject lateinit var repo: ModelRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { runCatching { repo.unload() } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "com.localchat.UNLOAD_MODEL"
    }
}
