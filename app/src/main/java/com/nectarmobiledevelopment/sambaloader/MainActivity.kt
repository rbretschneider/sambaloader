package com.nectarmobiledevelopment.sambaloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nectarmobiledevelopment.sambaloader.ui.home.HomeScreen
import com.nectarmobiledevelopment.sambaloader.ui.pairing.PairingScreen
import com.nectarmobiledevelopment.sambaloader.ui.settings.SettingsScreen
import com.nectarmobiledevelopment.sambaloader.ui.theme.SambaloaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SambaloaderTheme {
                RequestMediaPermissions()
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onPairClick = { navController.navigate(Routes.PAIRING) },
                            onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.PAIRING) {
                        PairingScreen(onFinished = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    private object Routes {
        const val HOME = "home"
        const val PAIRING = "pairing"
        const val SETTINGS = "settings"
    }
}

/**
 * Asks for media read access once on first launch. Without it the app
 * sees an empty camera roll and silently backs nothing up — the failure
 * mode FRD §8.9 warns about. Full partial-access handling is M6.
 */
@Composable
private fun RequestMediaPermissions() {
    val context = LocalContext.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val missing = permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            launcher.launch(permissions)
        }
    }
}
