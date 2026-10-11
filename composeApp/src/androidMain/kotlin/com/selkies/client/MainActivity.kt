package com.selkies.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.selkies.client.platform.AndroidPlatform.init(this)
        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            com.selkies.client.platform.AndroidPlatform.release()
        }
    }
}
