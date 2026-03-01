package com.example.grandfatherclock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.example.grandfatherclock.ui.theme.GrandfatherClockTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)

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

        setContent {
            GrandfatherClockTheme {
                ClockTunerScreen(
                    viewModel = viewModel,
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
}
