package com.opensiri.agent.bootstrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensiri.agent.bootstrap.ui.ChainProgressScreen
import com.opensiri.agent.bootstrap.ui.InstallViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: InstallViewModel = viewModel()
            ChainProgressScreen(viewModel)
        }
    }
}
