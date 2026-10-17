package com.intervaltimer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * High-precision, zero-asset acoustic engine for Android.
 * Uses AudioAttributes.USAGE_MEDIA / CONTENT_TYPE_MUSIC so all sound output
 * is directly and accurately regulated by the system media volume slider (STREAM_MUSIC).
 *
 * Supports:
 * - Direct regulation by hardware volume keys and system Media slider
 * - Audio focus transient ducking (smoothly dips Spotify / YouTube Music during beeps)
 * - Anti-pop sinusoidal synthesis with harmonics for high audibility
 * - Synchronized haptic feedback (vibrations) for gym environments
 */
class AndroidAudioEngine(private val context: Context? = null) {

    private val audioScope = CoroutineScope(Dispatchers.Default)
    private val sampleRate = 44100

    private val vibrator: Vibrator? by lazy {
        try {
            if (context == null) null
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            vibrator?.let { v ->
                if (!v.hasVibrator()) return@let
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Plays a rich harmonic tone with smooth attack/decay envelope.
     * Tied directly to system Media volume (USAGE_MEDIA).
     */
    fun playTone(
        frequencyHz: Double,
        durationMs: Int,
        volume: Float = 0.9f,
        withDucking: Boolean = true
    ) {
        audioScope.launch {
            var track: AudioTrack? = null
            var focusGranted = false
            var focusRequest: AudioFocusRequest? = null
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            try {
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val generatedSnd = ShortArray(numSamples)
                val rampSamples = (sampleRate * 0.012).toInt().coerceAtLeast(64) // 12ms anti-click ramp

                for (i in 0 until numSamples) {
                    val angle1 = 2.0 * Math.PI * i / (sampleRate / frequencyHz)
                    val angle2 = 2.0 * Math.PI * i / (sampleRate / (frequencyHz * 2.0))
                    // 80% fundamental + 20% second harmonic for crispness through phone speaker
                    var sample = 0.80 * sin(angle1) + 0.20 * sin(angle2)

                    // Attack envelope
                    if (i < rampSamples) {
                        sample *= (i.toDouble() / rampSamples)
                    }
                    // Decay envelope
                    if (i > numSamples - rampSamples) {
                        sample *= ((numSamples - i).toDouble() / rampSamples)
                    }

                    val s = (sample * 32767.0 * volume).toInt().coerceIn(-32768, 32767)
                    generatedSnd[i] = s.toShort()
                }

                // USAGE_MEDIA + CONTENT_TYPE_MUSIC ensures the sound is regulated by
                // the Android system Media volume slider and hardware volume buttons.
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                // Transient ducking of background music
                if (withDucking && audioManager != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(audioAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .setWillPauseWhenDucked(false)
                            .build()
                        val res = audioManager.requestAudioFocus(focusRequest)
                        focusGranted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    } else {
                        @Suppress("DEPRECATION")
                        val res = audioManager.requestAudioFocus(
                            null,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                        )
                        focusGranted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    }
                }

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(generatedSnd.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(generatedSnd, 0, generatedSnd.size)
                track.play()

                // Wait non-blocking for tone completion
                delay(durationMs.toLong() + 30)
            } catch (e: Exception) {
                System.err.println("AndroidAudioEngine error: ${e.message}")
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {}

                if (focusGranted && audioManager != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                            audioManager.abandonAudioFocusRequest(focusRequest)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(null)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Start of WORK interval: sharp high-pitch dual whistle + double vibration
     */
    fun playWorkSignal() {
        audioScope.launch {
            vibrate(longArrayOf(0, 100, 70, 140))
            playTone(880.0, 180, 0.95f)
            delay(190)
            playTone(1046.5, 300, 1.0f)
        }
    }

    /**
     * Start of REST interval: low soothing chime + single vibration
     */
    fun playRestSignal() {
        audioScope.launch {
            vibrate(longArrayOf(0, 180))
            playTone(440.0, 320, 0.9f)
        }
    }

    /**
     * Metronome countdown (3, 2, 1) before phase change
     */
    fun playMetronomeTick(remainingSeconds: Int) {
        val freq = when (remainingSeconds) {
            3 -> 659.25 // E5
            2 -> 783.99 // G5
            1 -> 987.77 // B5
            else -> 659.25
        }
        vibrate(longArrayOf(0, 40))
        playTone(freq, 110, 0.85f, withDucking = false)
    }

    /**
     * End of last set horn signal
     */
    fun playFinalSetHorn() {
        audioScope.launch {
            vibrate(longArrayOf(0, 150, 100, 250))
            playTone(587.33, 180, 0.9f)
            delay(200)
            playTone(880.0, 450, 1.0f)
        }
    }

    /**
     * Workout completed fanfare
     */
    fun playCompletionFanfare() {
        audioScope.launch {
            vibrate(longArrayOf(0, 100, 60, 100, 60, 250))
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50) // C5, E5, G5, C6
            for (i in notes.indices) {
                val duration = if (i == notes.size - 1) 550 else 180
                playTone(notes[i], duration, 0.95f)
                delay(190)
            }
        }
    }
}

