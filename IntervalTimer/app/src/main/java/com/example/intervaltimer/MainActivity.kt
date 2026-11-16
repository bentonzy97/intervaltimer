package com.example.intervaltimer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun sendCmd(ctx: Context, action: String) {
    ctx.startService(Intent(ctx, TimerService::class.java).setAction(action))
}

private fun num(s: String): Long = s.toLongOrNull() ?: 0L

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val ui by TimerState.state.collectAsState()
    val prefs = remember { Prefs.of(ctx) }

    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("vibrate", true)) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice", true)) }
    var tab by remember { mutableStateOf(0) }
    var totMin by remember { mutableStateOf("5") }
    var totSec by remember { mutableStateOf("0") }
    var intMin by remember { mutableStateOf("0") }
    var intSec by remember { mutableStateOf("30") }
    var error by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val active = ui.status == Status.RUNNING || ui.status == Status.PAUSED
    val mode = if (active) ui.mode else if (tab == 0) Mode.TIMER else Mode.STOPWATCH
    val isTimer = mode == Mode.TIMER
    val inputTotal = (num(totMin) * 60 + num(totSec)) * 1000
    val inputInterval = (num(intMin) * 60 + num(intSec)) * 1000
    val hasRun = ui.status != Status.IDLE && ui.mode == mode

    val shownMs = if (hasRun) {
        if (isTimer) ui.totalMs - ui.elapsedMs else ui.elapsedMs
    } else {
        if (isTimer) inputTotal else 0L
    }

    val note = when {
        ui.status == Status.PAUSED -> "Paused"
        ui.status == Status.FINISHED && hasRun -> "Time's up!"
        active && ui.intervalMs > 0 -> {
            val next = (ui.elapsedMs / ui.intervalMs + 1) * ui.intervalMs
            if (ui.mode == Mode.TIMER && next >= ui.totalMs) ""
            else "Next beep in ${fmt(next - ui.elapsedMs, true)}"
        }
        else -> ""
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TabRow(selectedTabIndex = tab) {
            listOf("Timer", "Stopwatch").forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = {
                        if (!active) {
                            TimerState.state.value = UiState()
                            tab = i
                            error = null
                        }
                    },
                    text = { Text(title) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(fmt(shownMs, isTimer), fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Text(note, fontSize = 18.sp, modifier = Modifier.height(28.dp))
        Spacer(Modifier.height(12.dp))

        if (!active) {
            if (isTimer) {
                Text("Total time", modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("min", totMin, { totMin = it }, Modifier.weight(1f))
                    NumField("sec", totSec, { totSec = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            Text("Beep every", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("min", intMin, { intMin = it }, Modifier.weight(1f))
                NumField("sec", intSec, { intSec = it }, Modifier.weight(1f))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui.status) {
                Status.RUNNING -> {
                    Button(onClick = { sendCmd(ctx, TimerService.ACTION_PAUSE) }) { Text("Pause") }
                    OutlinedButton(onClick = { sendCmd(ctx, TimerService.ACTION_RESET) }) { Text("Reset") }
                }
                Status.PAUSED -> {
                    Button(onClick = { sendCmd(ctx, TimerService.ACTION_RESUME) }) { Text("Resume") }
                    OutlinedButton(onClick = { sendCmd(ctx, TimerService.ACTION_RESET) }) { Text("Reset") }
                }
                else -> Button(onClick = {
                    if (inputInterval <= 0) {
                        error = "Set a beep interval of at least 1 second"
                    } else if (isTimer && inputTotal <= 0) {
                        error = "Set a total time"
                    } else {
                        error = null
                        val i = Intent(ctx, TimerService::class.java)
                            .setAction(TimerService.ACTION_START)
                            .putExtra(TimerService.EXTRA_MODE, if (isTimer) "timer" else "stopwatch")
                            .putExtra(TimerService.EXTRA_TOTAL, inputTotal)
                            .putExtra(TimerService.EXTRA_INTERVAL, inputInterval)
                        ContextCompat.startForegroundService(ctx, i)
                    }
                }) { Text("Start") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = sound,
                onClick = { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() },
                label = { Text("Beep") }
            )
            FilterChip(
                selected = vibrate,
                onClick = { vibrate = !vibrate; prefs.edit().putBoolean("vibrate", vibrate).apply() },
                label = { Text("Vibrate") }
            )
            FilterChip(
                selected = voice,
                onClick = { voice = !voice; prefs.edit().putBoolean("voice", voice).apply() },
                label = { Text("Voice") }
            )
        }

        Spacer(Modifier.height(12.dp))
        val laps = if (hasRun) ui.laps else emptyList()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(laps.reversed()) { idx, t ->
                Text(
                    "${if (isTimer) "Beep" else "Lap"} ${laps.size - idx}  -  ${fmt(t, false)}",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
