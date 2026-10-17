package com.intervaltimer.core

/**
 * Phase enumeration matching ANSI C TimerPhase in interval_timer.h
 */
enum class TimerPhase(val id: Int, val displayNameRu: String, val displayNameEn: String) {
    IDLE(0, "Ожидание", "Idle"),
    PREP(1, "Подготовка", "Preparation"),
    WORK(2, "Работа", "Work"),
    REST(3, "Отдых", "Rest"),
    CYCLE_REST(4, "Отдых между циклами", "Cycle Rest"),
    PAUSED(5, "Пауза", "Paused"),
    COMPLETED(6, "Завершено", "Completed");

    companion object {
        fun fromId(id: Int): TimerPhase = entries.firstOrNull { it.id == id } ?: IDLE
    }
}

/**
 * Interval set definition matching C IntervalSet
 */
data class IntervalSet(
    @JvmField val name: String,
    @JvmField val workSeconds: Int,
    @JvmField val restSeconds: Int,
    @JvmField val soundWorkFreq: Int = 880,
    @JvmField val soundRestFreq: Int = 440,
    val id: String = ""
)

/**
 * Workout plan matching C WorkoutPlan and Web schema
 */
data class WorkoutPlan(
    val name: String,
    val prepSeconds: Int = 5,
    val cycles: Int = 1,
    val cycleRestSeconds: Int = 30,
    val sets: List<IntervalSet>,
    val id: String = "",
    val description: String = "",
    val isCustom: Boolean = false
)

/**
 * Real-time state snapshot
 */
data class TimerSnapshot(
    val phase: TimerPhase,
    val secondsRemaining: Int,
    val totalPhaseSeconds: Int,
    val currentSetIndex: Int,
    val currentCycleIndex: Int,
    val totalElapsedSeconds: Int,
    val isRunning: Boolean,
    val isPaused: Boolean,
    val workoutName: String = "Табата (Классика)"
) {
    val displaySeconds: Int
        get() = maxOf(0, secondsRemaining)

    val progressFraction: Float
        get() = if (totalPhaseSeconds > 0) {
            1f - (displaySeconds.toFloat() / totalPhaseSeconds.toFloat())
        } else if (phase == TimerPhase.COMPLETED) {
            1f
        } else 0f
}

/**
 * Callback interface invoked from C JNI on state changes
 */
interface NativeTimerListener {
    fun onNativeTick(phase: Int, secondsRemaining: Int, totalPhaseSeconds: Int, currentSet: Int, currentCycle: Int, totalElapsed: Int)
    fun onNativePhaseChange(oldPhase: Int, newPhase: Int)
    fun onNativeMetronome(remainingSeconds: Int)
    fun onNativeComplete(totalElapsedSeconds: Int)
}

/**
 * JNI Bridge singleton providing access to compiled C engine (libinterval_timer_native.so)
 */
object IntervalTimerNative {
    var activeWorkoutName: String = "Табата (Классика)"
        private set

    init {
        try {
            System.loadLibrary("interval_timer_native")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("IntervalTimerNative: Failed to load shared library: ${e.message}")
        }
    }

    external fun nativeInit()
    external fun nativeRegisterListener(listener: NativeTimerListener)
    external fun nativeUnregisterListener()

    external fun nativeSetPlan(
        name: String,
        prepSeconds: Int,
        cycles: Int,
        cycleRestSeconds: Int,
        sets: Array<IntervalSet>
    )

    external fun nativeStart()
    external fun nativePause()
    external fun nativeResume()
    external fun nativeTogglePause()
    external fun nativeReset()
    external fun nativeSkipNext()
    external fun nativeTickSecond()

    external fun nativeGetPhase(): Int
    external fun nativeGetSecondsRemaining(): Int
    external fun nativeGetTotalPhaseSeconds(): Int
    external fun nativeGetCurrentSet(): Int
    external fun nativeGetCurrentCycle(): Int
    external fun nativeGetTotalElapsed(): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeIsPaused(): Boolean

    /**
     * High-level helper returning snapshot
     */
    fun getSnapshot(): TimerSnapshot {
        return TimerSnapshot(
            phase = TimerPhase.fromId(nativeGetPhase()),
            secondsRemaining = nativeGetSecondsRemaining(),
            totalPhaseSeconds = nativeGetTotalPhaseSeconds(),
            currentSetIndex = nativeGetCurrentSet(),
            currentCycleIndex = nativeGetCurrentCycle(),
            totalElapsedSeconds = nativeGetTotalElapsed(),
            isRunning = nativeIsRunning(),
            isPaused = nativeIsPaused(),
            workoutName = activeWorkoutName
        )
    }

    /**
     * Configure plan convenience method
     */
    fun configurePlan(plan: WorkoutPlan) {
        activeWorkoutName = plan.name
        nativeSetPlan(
            name = plan.name,
            prepSeconds = plan.prepSeconds,
            cycles = plan.cycles,
            cycleRestSeconds = plan.cycleRestSeconds,
            sets = plan.sets.toTypedArray()
        )
    }

    fun getDefaultTabataPlan(): WorkoutPlan {
        return WorkoutPlan(
            id = "tabata-classic",
            name = "Табата (Классика)",
            description = "8 раундов по 20 сек работы и 10 сек отдыха",
            prepSeconds = 5,
            cycles = 1,
            cycleRestSeconds = 0,
            sets = List(8) { i ->
                IntervalSet(name = "Интервал ${i + 1}", workSeconds = 20, restSeconds = 10)
            }
        )
    }

    fun getDefaultBoxingPlan(): WorkoutPlan {
        return WorkoutPlan(
            id = "boxing-rounds",
            name = "Бокс (3 мин раунды)",
            description = "5 раундов по 3 минуты с минутой отдыха",
            prepSeconds = 10,
            cycles = 5,
            cycleRestSeconds = 60,
            sets = listOf(
                IntervalSet(name = "Раунд", workSeconds = 180, restSeconds = 60)
            )
        )
    }

    fun getDefaultHIITPlan(): WorkoutPlan {
        return WorkoutPlan(
            id = "hiit-circuit",
            name = "HIIT Интервалы (40/20)",
            description = "3 круга по 5 упражнений",
            prepSeconds = 10,
            cycles = 3,
            cycleRestSeconds = 45,
            sets = List(5) { i ->
                IntervalSet(name = "Упражнение ${i + 1}", workSeconds = 40, restSeconds = 20)
            }
        )
    }

    fun getDefaultStretchPlan(): WorkoutPlan {
        return WorkoutPlan(
            id = "deep-stretch",
            name = "Растяжка / Заминка",
            description = "Плавные позиции по 45 секунд",
            prepSeconds = 5,
            cycles = 1,
            cycleRestSeconds = 0,
            sets = List(6) { i ->
                IntervalSet(name = "Поза ${i + 1}", workSeconds = 45, restSeconds = 15)
            }
        )
    }

    /**
     * Loads a standard Tabata workout plan (5s prep, 8 sets of 20s work / 10s rest)
     */
    fun loadDefaultTabata() {
        configurePlan(getDefaultTabataPlan())
    }
}
