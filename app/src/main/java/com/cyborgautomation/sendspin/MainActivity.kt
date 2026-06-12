package com.cyborgautomation.sendspin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cyborgautomation.sendspin.service.SendspinService
import com.cyborgautomation.sendspin.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App()
        }
    }

    fun startService(serverUrl: String) {
        val intent = Intent(this, SendspinService::class.java).apply {
            action = "CONNECT"
            putExtra("server_url", serverUrl)
        }
        startForegroundService(intent)
    }

    fun stopService() {
        val intent = Intent(this, SendspinService::class.java).apply {
            action = "DISCONNECT"
        }
        startService(intent)
    }
}
