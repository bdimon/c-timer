package com.intervaltimer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.intervaltimer.core.IntervalTimerNative
import com.intervaltimer.core.TimerPhase
import com.intervaltimer.core.WorkoutPlan
import com.intervaltimer.core.WorkoutPlanStorage
import com.intervaltimer.service.TimerForegroundService

class MainActivity : ComponentActivity() {

    private val timerServiceState = mutableStateOf<TimerForegroundService?>(null)
    private var isBound = false
    private lateinit var planStorage: WorkoutPlanStorage

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerForegroundService.LocalBinder
            val s = binder.getService()
            timerServiceState.value = s
            isBound = true

            // If timer is idle, load default plan
            if (s.timerState.value.phase == TimerPhase.IDLE) {
                val plans = planStorage.getAllPlans()
                val defaultPlan = plans.firstOrNull() ?: IntervalTimerNative.getDefaultTabataPlan()
                s.loadPlan(defaultPlan)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            timerServiceState.value = null
            isBound = false
        }
    }

    private fun sendTimerAction(action: String) {
        val serviceIntent = Intent(this, TimerForegroundService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        planStorage = WorkoutPlanStorage(this)

        // Bind hardware volume keys to media volume (STREAM_MUSIC)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val serviceIntent = Intent(this, TimerForegroundService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Slate 900
                ) {
                    val service = timerServiceState.value
                    val snapshot by (service?.timerState?.collectAsState() ?: remember {
                        mutableStateOf(IntervalTimerNative.getSnapshot())
                    })

                    var currentScreen by remember { mutableStateOf("timer") } // "timer" or "plans"
                    var showSyncDialog by remember { mutableStateOf(false) }
                    var planListVersion by remember { mutableStateOf(0) }

                    val plans = remember(planListVersion) {
                        planStorage.getAllPlans()
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Navigation Header
                        TopBarHeader(
                            activeScreen = currentScreen,
                            onScreenChange = { currentScreen = it },
                            onOpenSync = { showSyncDialog = true }
                        )

                        // Battery Saver Warning if optimization is active
                        BatteryOptimizationBanner(
                            context = this@MainActivity
                        )

                        // Active Screen
                        if (currentScreen == "timer") {
                            TimerMainScreen(
                                snapshot = snapshot,
                                onStart = {
                                    if (timerServiceState.value != null) {
                                        timerServiceState.value?.startWorkout()
                                    } else {
                                        sendTimerAction(TimerForegroundService.ACTION_START)
                                    }
                                },
                                onPause = {
                                    if (timerServiceState.value != null) {
                                        timerServiceState.value?.pauseWorkout()
                                    } else {
                                        sendTimerAction(TimerForegroundService.ACTION_PAUSE)
                                    }
                                },
                                onResume = {
                                    if (timerServiceState.value != null) {
                                        timerServiceState.value?.resumeWorkout()
                                    } else {
                                        sendTimerAction(TimerForegroundService.ACTION_RESUME)
                                    }
                                },
                                onSkip = {
                                    if (timerServiceState.value != null) {
                                        timerServiceState.value?.skipNext()
                                    } else {
                                        sendTimerAction(TimerForegroundService.ACTION_SKIP)
                                    }
                                },
                                onReset = {
                                    if (timerServiceState.value != null) {
                                        timerServiceState.value?.stopWorkout()
                                    } else {
                                        sendTimerAction(TimerForegroundService.ACTION_STOP)
                                    }
                                }
                            )
                        } else {
                            WorkoutPlansScreen(
                                plans = plans,
                                activeWorkoutName = snapshot.workoutName,
                                onSelectPlan = { plan ->
                                    val s = timerServiceState.value
                                    if (s != null) {
                                        s.stopWorkout()
                                        s.loadPlan(plan)
                                    } else {
                                        IntervalTimerNative.configurePlan(plan)
                                    }
                                    currentScreen = "timer"
                                    Toast.makeText(this@MainActivity, "Выбран план: ${plan.name}", Toast.LENGTH_SHORT).show()
                                },
                                onDeletePlan = { planId ->
                                    planStorage.deletePlan(planId)
                                    planListVersion++
                                    Toast.makeText(this@MainActivity, "Профиль удален", Toast.LENGTH_SHORT).show()
                                },
                                onExportPlan = { plan ->
                                    val json = planStorage.exportPlanToJson(plan)
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("WorkoutPlan", json))
                                    Toast.makeText(this@MainActivity, "JSON скопирован в буфер!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }

                    // Web Sync Dialog
                    if (showSyncDialog) {
                        SyncWithWebDialog(
                            onDismiss = { showSyncDialog = false },
                            onImport = { payload ->
                                val imported = planStorage.importPlanFromString(payload)
                                if (imported != null) {
                                    planListVersion++
                                    val s = timerServiceState.value
                                    if (s != null) {
                                        s.stopWorkout()
                                        s.loadPlan(imported)
                                    } else {
                                        IntervalTimerNative.configurePlan(imported)
                                    }
                                    showSyncDialog = false
                                    currentScreen = "timer"
                                    Toast.makeText(this@MainActivity, "Синхронизировано: ${imported.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Ошибка: неверный формат синхронизации", Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun TopBarHeader(
    activeScreen: String,
    onScreenChange: (String) -> Unit,
    onOpenSync: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0F1D))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "INTERVAL TIMER C",
                color = Color(0xFF10B981),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "NDK Core v2.0",
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Screen Switcher Tabs
        Row(
            modifier = Modifier
                .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(
                        if (activeScreen == "timer") Color(0xFF10B981) else Color.Transparent,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable { onScreenChange("timer") }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Таймер",
                    color = if (activeScreen == "timer") Color(0xFF0F172A) else Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .background(
                        if (activeScreen == "plans") Color(0xFF10B981) else Color.Transparent,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable { onScreenChange("plans") }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Планы",
                    color = if (activeScreen == "plans") Color(0xFF0F172A) else Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Sync with Web Button
        FilledTonalButton(
            onClick = onOpenSync,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF38BDF8)
            )
        ) {
            Text(
                text = "Web Sync",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun BatteryOptimizationBanner(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    var isIgnoring by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        )
    }

    if (!isIgnoring && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF451A03)) // Dark Amber
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚡ Для надежной работы при выкл. экране отключите оптимизацию батареи",
                color = Color(0xFFFDE68A),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
            ) {
                Text(text = "Разрешить", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WorkoutPlansScreen(
    plans: List<WorkoutPlan>,
    activeWorkoutName: String,
    onSelectPlan: (WorkoutPlan) -> Unit,
    onDeletePlan: (String) -> Unit,
    onExportPlan: (WorkoutPlan) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Тренировочные Программы (${plans.size})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Выберите тренировку или импортируйте профиль из веб-версии",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        items(plans) { plan ->
            val isActive = plan.name == activeWorkoutName
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) Color(0xFF1E293B) else Color(0xFF111827)
                ),
                shape = RoundedCornerShape(16.dp),
                border = if (isActive) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF10B981))) else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = plan.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isActive) {
                            Text(
                                text = "АКТИВЕН",
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (plan.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = plan.description,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Badges
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgePill(label = "Сетов: ${plan.sets.size}")
                        BadgePill(label = "Циклов: ${plan.cycles}")
                        if (plan.prepSeconds > 0) {
                            BadgePill(label = "Разминка: ${plan.prepSeconds}с")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onExportPlan(plan) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(text = "Экспорт", fontSize = 11.sp)
                            }

                            if (plan.isCustom) {
                                FilledTonalButton(
                                    onClick = { onDeletePlan(plan.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3F1D24), contentColor = Color(0xFFF87171)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = "Удалить", fontSize = 11.sp)
                                }
                            }
                        }

                        Button(
                            onClick = { onSelectPlan(plan) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(text = if (isActive) "Выбран" else "Выбрать", color = Color(0xFF0F172A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BadgePill(label: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SyncWithWebDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Синхронизация с Web",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Вставьте код синхронизации (itimer://...) или JSON тренировочного плана из веб-версии:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = {
                        Text(
                            text = "itimer://plan?d=... или {\"name\": ...}",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "Отмена", color = Color(0xFF94A3B8))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onImport(inputText) },
                        enabled = inputText.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Text(text = "Импортировать", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TimerMainScreen(
    snapshot: com.intervaltimer.core.TimerSnapshot,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit
) {
    val phaseColor = when (snapshot.phase) {
        TimerPhase.PREP -> Color(0xFFF59E0B)       // Amber
        TimerPhase.WORK -> Color(0xFF10B981)       // Emerald
        TimerPhase.REST -> Color(0xFF3B82F6)       // Blue
        TimerPhase.CYCLE_REST -> Color(0xFF8B5CF6) // Purple
        TimerPhase.PAUSED -> Color(0xFFEF4444)     // Red
        TimerPhase.COMPLETED -> Color(0xFFEC4899)  // Pink
        TimerPhase.IDLE -> Color(0xFF64748B)       // Slate
    }

    val minutes = snapshot.displaySeconds / 60
    val seconds = snapshot.displaySeconds % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header & Workout Title
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = snapshot.phase.displayNameRu.uppercase(),
                color = phaseColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = snapshot.workoutName,
                color = Color(0xFFE2E8F0),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Circular Timer Display
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(280.dp)
        ) {
            CircularProgressIndicator(
                progress = { snapshot.progressFraction },
                modifier = Modifier.fillMaxSize(),
                color = phaseColor,
                strokeWidth = 14.dp,
                trackColor = Color(0xFF1E293B)
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timeFormatted,
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Сет ${snapshot.currentSetIndex + 1} • Цикл ${snapshot.currentCycleIndex + 1}",
                    color = Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Control Buttons (All identical 68.dp circles)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Reset Button (↺)
            FilledTonalButton(
                onClick = onReset,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFFCBD5E1)
                )
            ) {
                ResetMnemonic(color = Color(0xFFCBD5E1))
            }

            // 2. Play / Pause Button (▶ / ❚❚)
            Button(
                onClick = {
                    if (!snapshot.isRunning) onStart()
                    else if (snapshot.isPaused) onResume()
                    else onPause()
                },
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = phaseColor)
            ) {
                if (!snapshot.isRunning || snapshot.isPaused) {
                    PlayMnemonic(color = Color.White)
                } else {
                    PauseMnemonic(color = Color.White)
                }
            }

            // 3. Skip Button (>>)
            FilledTonalButton(
                onClick = onSkip,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFFCBD5E1)
                )
            ) {
                SkipMnemonic(color = Color(0xFFCBD5E1))
            }
        }
    }
}

@Composable
fun PlayMnemonic(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.16f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.84f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun PauseMnemonic(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val barWidth = w * 0.24f
        val barHeight = h * 0.68f
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.16f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.56f, h * 0.16f),
            size = Size(barWidth, barHeight),
            cornerRadius = corner
        )
    }
}

@Composable
fun SkipMnemonic(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val p1 = Path().apply {
            moveTo(w * 0.10f, h * 0.18f)
            lineTo(w * 0.48f, h * 0.50f)
            lineTo(w * 0.10f, h * 0.82f)
            close()
        }
        val p2 = Path().apply {
            moveTo(w * 0.48f, h * 0.18f)
            lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.48f, h * 0.82f)
            close()
        }
        drawPath(p1, color)
        drawPath(p2, color)
    }
}

@Composable
fun ResetMnemonic(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        val r = w * 0.35f
        val cx = w * 0.50f
        val cy = w * 0.50f

        drawArc(
            color = color,
            startAngle = -220f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val arrowPath = Path().apply {
            moveTo(cx - r - 4.dp.toPx(), cy - 7.dp.toPx())
            lineTo(cx - r + 3.dp.toPx(), cy + 1.dp.toPx())
            lineTo(cx - r + 7.dp.toPx(), cy - 6.dp.toPx())
            close()
        }
        drawPath(arrowPath, color)
    }
}
