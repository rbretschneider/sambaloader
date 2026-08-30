package com.nectarmobiledevelopment.sambaloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nectarmobiledevelopment.sambaloader.ui.home.HomeScreen
import com.nectarmobiledevelopment.sambaloader.ui.pairing.PairingScreen
import com.nectarmobiledevelopment.sambaloader.ui.theme.SambaloaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SambaloaderTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(onPairClick = { navController.navigate(Routes.PAIRING) })
                    }
                    composable(Routes.PAIRING) {
                        PairingScreen(onFinished = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    private object Routes {
        const val HOME = "home"
        const val PAIRING = "pairing"
    }
}
