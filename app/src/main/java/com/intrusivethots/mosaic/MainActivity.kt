package com.intrusivethots.mosaic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.ui.screens.MainScreen
import com.intrusivethots.mosaic.ui.screens.MainViewModel
import com.intrusivethots.mosaic.ui.theme.MosaicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = ProjectRepository(applicationContext)

        setContent {
            MosaicTheme {
                val viewModel: MainViewModel = viewModel {
                    MainViewModel(repository)
                }
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
