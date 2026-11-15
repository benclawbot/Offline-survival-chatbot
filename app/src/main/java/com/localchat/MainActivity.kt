package com.localchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.localchat.ui.LocalChatApp
import com.localchat.ui.settings.SettingsViewModel
import com.localchat.ui.theme.LocalChatTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore: уведомления опциональны */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotifications()
        OfflineAssetsBootstrap.start(applicationContext)
        setContent {
            val setup by OfflineAssetsBootstrap.state.collectAsState()
            val ready = setup is OfflineAssetsBootstrap.State.Ready
            if (ready) {
                val settingsVm: SettingsViewModel = hiltViewModel()
                val settings by settingsVm.settings.collectAsState()
                LocalChatTheme(themeMode = settings.theme, dynamicColor = settings.dynamicColor) {
                    LocalChatApp()
                }
            } else {
                LocalChatTheme {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        when (val state = setup) {
                            OfflineAssetsBootstrap.State.Preparing -> {
                                CircularProgressIndicator()
                                Text("Preparing offline resources…", Modifier.padding(top = 16.dp))
                            }
                            is OfflineAssetsBootstrap.State.Progress -> {
                                CircularProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) })
                                Text(state.label, Modifier.padding(top = 16.dp))
                                Text("Keep the app open during first setup", Modifier.padding(top = 8.dp))
                            }
                            is OfflineAssetsBootstrap.State.Failed -> {
                                Text("Offline setup could not finish")
                                Text(state.message, Modifier.padding(top = 12.dp))
                                Button(onClick = { OfflineAssetsBootstrap.start(applicationContext) }, Modifier.padding(top = 16.dp)) {
                                    Text("Retry")
                                }
                            }
                            OfflineAssetsBootstrap.State.Ready -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
