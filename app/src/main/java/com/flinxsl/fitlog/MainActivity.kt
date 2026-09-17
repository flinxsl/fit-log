package com.flinxsl.fitlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flinxsl.fitlog.ui.theme.FitlogTheme
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitlogTheme {
                val vm: AppState = viewModel()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Ink,
                ) { innerPadding ->
                    App(vm, Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun App(vm: AppState, modifier: Modifier = Modifier) {
    if (vm.loading) {
        Box(modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text("Loading…", color = TextSecondary)
        }
        return
    }
    // Read-only for now. Home, Session and the routine builder land next.
    ScreenHistory(vm.log, vm.loadWarning, modifier)
}
