package com.nectarmobiledevelopment.sambaloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nectarmobiledevelopment.sambaloader.ui.home.HomeScreen
import com.nectarmobiledevelopment.sambaloader.ui.theme.SambaloaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SambaloaderTheme {
                HomeScreen()
            }
        }
    }
}
