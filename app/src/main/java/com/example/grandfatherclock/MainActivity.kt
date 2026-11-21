package com.example.grandfatherclock

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.grandfatherclock.ui.ClockTunerScreen
import com.example.grandfatherclock.ui.SessionHistoryScreen
import com.example.grandfatherclock.ui.theme.GrandfatherClockTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)
    private var hasSilenceAccess by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.wavOutputDir = getExternalFilesDir(null)

        // Debug builds can replay a recording through the app instead of the mic:
        // adb shell am start -n com.example.grandfatherclock/.MainActivity \
        //     --es replay_wav <path on device> --ef replay_speed 4
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("replay_wav")?.let { viewModel.replayWav = File(it) }
            viewModel.replaySpeed = intent.getFloatExtra("replay_speed", 1f).toDouble()
        }

        setContent {
            GrandfatherClockTheme {
                if (showHistory) {
                    SessionHistoryScreen(
                        sessionStore = viewModel.sessionStore,
                        onBack = { showHistory = false },
                    )
                } else {
                    ClockTunerScreen(
                        viewModel = viewModel,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onShowHistory = { showHistory = true },
                        hasSilenceAccess = hasSilenceAccess,
                        onRequestSilenceAccess = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user grants DND access in system settings, so re-check on return.
        hasSilenceAccess = viewModel.phoneSilencer.hasAccess
    }
}
