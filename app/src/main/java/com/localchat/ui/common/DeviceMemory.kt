package com.localchat.ui.common

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

data class DeviceMemorySnapshot(
    val totalMb: Long,
    val availMb: Long,
    val appMb: Long,
) {
    val usedMb: Long get() = (totalMb - availMb).coerceAtLeast(0)
    val usedPercent: Int
        get() = if (totalMb > 0) ((usedMb * 100) / totalMb).toInt().coerceIn(0, 100) else 0
}

@Composable
fun rememberDeviceMemorySnapshot(): State<DeviceMemorySnapshot> {
    val context = LocalContext.current
    return produceState(initialValue = readDeviceMemory(context), context) {
        while (true) {
            value = readDeviceMemory(context)
            delay(1500)
        }
    }
}

fun readDeviceMemory(context: Context): DeviceMemorySnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val mb = 1024L * 1024L
    val app = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() +
        Debug.getNativeHeapAllocatedSize()) / mb
    return DeviceMemorySnapshot(
        totalMb = info.totalMem / mb,
        availMb = info.availMem / mb,
        appMb = app,
    )
}
