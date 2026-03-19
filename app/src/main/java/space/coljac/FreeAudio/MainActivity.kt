package space.coljac.FreeAudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.media3.common.util.UnstableApi
import space.coljac.FreeAudio.ui.theme.FreeAudioTheme
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.navigation.AppNavigation

private const val TAG = "MainActivity"
private const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val viewModel: AudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check and request battery optimisation exemption (one-time prompt)
        checkBatteryOptimization()

        setContent {
            FreeAudioTheme {
                AppNavigation(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    /**
     * Check if the app is exempted from battery optimisation.
     * If not, prompt the user once to exempt it. This prevents the OS from
     * aggressively killing the playback service when the screen is off.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getPreferences(Context.MODE_PRIVATE)
                if (!prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) {
                    prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not request battery optimisation exemption", e)
                    }
                } else {
                    Log.d(TAG, "Battery optimisation prompt already shown")
                }
            } else {
                Log.d(TAG, "App is exempted from battery optimisation")
            }
        }
    }
}
