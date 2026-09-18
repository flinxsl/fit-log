package com.flinxsl.fitlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Ink) { pad ->
                    App(vm, Modifier.padding(pad))
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

    // System back walks our own stack; falling through exits the app.
    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    when (val screen = vm.screen) {
        Screen.Home -> ScreenHome(vm, modifier)
        Screen.Session -> ScreenSession(vm, modifier)
        Screen.History -> ScreenHistory(vm.log, vm.loadWarning, { vm.back() }, modifier)
        Screen.Routines -> ScreenRoutines(vm, modifier)
        Screen.Settings -> ScreenSettings(vm, modifier)
        is Screen.DayEditor -> ScreenDayEditor(vm, screen.label, modifier)
        is Screen.ExerciseEditor -> ScreenExerciseEditor(vm, screen.label, screen.exerciseId, modifier)
    }
}
