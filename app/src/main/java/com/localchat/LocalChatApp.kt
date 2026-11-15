package com.localchat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LocalChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineAssetsBootstrap.start(this)
    }
}
