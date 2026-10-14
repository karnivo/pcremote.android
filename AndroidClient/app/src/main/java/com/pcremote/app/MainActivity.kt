package com.pcremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pcremote.app.network.PcRemoteClient
import com.pcremote.app.ui.PcRemoteAppUI

class MainActivity : ComponentActivity() {
    private lateinit var client: PcRemoteClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = PcRemoteClient(applicationContext)
        setContent {
            PcRemoteAppUI(client = client)
        }
    }
}
