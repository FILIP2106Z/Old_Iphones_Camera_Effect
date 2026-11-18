package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural 44.1 kHz 16-bit PCM retro audio synthesizer using SoundPool.
 * Generates zero-asset mechanical shutter clicks, dual-tone video beeps,
 * and subtle tap-to-focus lock ticks at startup.
 */
class RetroSoundEngine(context: Context) {

    private val sampleRate = 44100
    private val soundPool: SoundPool
    private var shutterSoundId: Int = 0
    private var videoStartSoundId: Int = 0
    private var videoStopSoundId: Int = 0
    private var focusTickSoundId: Int = 0
    private val loadedIds = mutableSetOf<Int>()

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attributes)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedIds.add(sampleId)
            }
        }

        val cacheDir = context.cacheDir
        val shutterFile = writeWavFile(File(cacheDir, "retro_shutter.wav"), synthesizeShutterClick())
        val startFile = writeWavFile(File(cacheDir, "retro_vid_start.wav"), synthesizeDualBeep(880.0, 1318.5))
        val stopFile = writeWavFile(File(cacheDir, "retro_vid_stop.wav"), synthesizeDualBeep(1318.5, 880.0))
        val focusFile = writeWavFile(File(cacheDir, "retro_focus_tick.wav"), synthesizeFocusTick())

        shutterSoundId = soundPool.load(shutterFile.absolutePath, 1)
        videoStartSoundId = soundPool.load(startFile.absolutePath, 1)
        videoStopSoundId = soundPool.load(stopFile.absolutePath, 1)
        focusTickSoundId = soundPool.load(focusFile.absolutePath, 1)
    }

    fun playShutterClick() {
        playIfLoaded(shutterSoundId, volume = 0.95f)
    }

    fun playVideoStartBeep() {
        playIfLoaded(videoStartSoundId, volume = 0.85f)
    }

    fun playVideoStopBeep() {
        playIfLoaded(videoStopSoundId, volume = 0.85f)
    }

    fun playFocusTick() {
        playIfLoaded(focusTickSoundId, volume = 0.45f)
    }

    private fun playIfLoaded(soundId: Int, volume: Float) {
        if (soundId != 0 && loadedIds.contains(soundId)) {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
        }
    }

    fun release() {
        soundPool.release()
    }

    /**
     * Dual-transient mechanical leaf shutter:
     * Burst 1 (open blade snap) at t=0ms, Burst 2 (return blade snap) at t=45ms.
     */
    private fun synthesizeShutterClick(): ShortArray {
        val totalMs = 95
        val totalSamples = (sampleRate * totalMs) / 1000
        val out = ShortArray(totalSamples)
        val rng = Random(2009)

        val secondTransientStart = (sampleRate * 45) / 1000
        var lpState1 = 0.0
        var lpState2 = 0.0

        for (i in 0 until totalSamples) {
            var sample = 0.0

            // First mechanical transient (0..28ms)
            if (i < (sampleRate * 28) / 1000) {
                val t = i.toDouble() / sampleRate
                val env = exp(-t * 175.0)
                val white = rng.nextDouble(-1.0, 1.0)
                lpState1 = lpState1 * 0.62 + white * 0.38
                val hpBurst = white - lpState1
                val springTone = sin(2.0 * PI * 2150.0 * t) * 0.32
                sample += (hpBurst * 0.85 + springTone) * env
            }

            // Second mechanical transient at +45ms (45..90ms)
            if (i >= secondTransientStart) {
                val localIdx = i - secondTransientStart
                val t = localIdx.toDouble() / sampleRate
                val env = exp(-t * 145.0)
                val white = rng.nextDouble(-1.0, 1.0)
                lpState2 = lpState2 * 0.56 + white * 0.44
                val hpBurst = white - lpState2
                val latchTone = sin(2.0 * PI * 1680.0 * t) * 0.38
                sample += (hpBurst * 0.90 + latchTone) * env * 0.92
            }

            out[i] = (sample.coerceIn(-0.98, 0.98) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * Classic dual-tone iOS 3/4 video record start/stop chime (80ms each tone).
     */
    private fun synthesizeDualBeep(freq1Hz: Double, freq2Hz: Double): ShortArray {
        val toneSamples = (sampleRate * 80) / 1000
        val gapSamples = (sampleRate * 10) / 1000
        val totalSamples = toneSamples * 2 + gapSamples
        val out = ShortArray(totalSamples)
        val fadeSamples = (sampleRate * 5) / 1000

        for (i in 0 until toneSamples) {
            val t = i.toDouble() / sampleRate
            val env = envelope(i, toneSamples, fadeSamples)
            val wave = (sin(2.0 * PI * freq1Hz * t) * 0.82 + sin(2.0 * PI * freq1Hz * 2.0 * t) * 0.12) * env
            out[i] = (wave * 0.72 * Short.MAX_VALUE).toInt().toShort()
        }

        val secondStart = toneSamples + gapSamples
        for (i in 0 until toneSamples) {
            val t = i.toDouble() / sampleRate
            val env = envelope(i, toneSamples, fadeSamples)
            val wave = (sin(2.0 * PI * freq2Hz * t) * 0.82 + sin(2.0 * PI * freq2Hz * 2.0 * t) * 0.12) * env
            out[secondStart + i] = (wave * 0.72 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * Soft subtle mechanical focus tick when tapping the viewfinder.
     */
    private fun synthesizeFocusTick(): ShortArray {
        val totalSamples = (sampleRate * 18) / 1000
        val out = ShortArray(totalSamples)
        val rng = Random(3642)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 320.0)
            val click = (sin(2.0 * PI * 1350.0 * t) * 0.55 + rng.nextDouble(-0.25, 0.25)) * env
            out[i] = (click.coerceIn(-0.9, 0.9) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun envelope(index: Int, total: Int, fade: Int): Double {
        return when {
            index < fade -> index.toDouble() / fade
            index > total - fade -> (total - index).toDouble() / fade
            else -> 1.0
        }.coerceIn(0.0, 1.0)
    }

    private fun writeWavFile(file: File, pcm: ShortArray): File {
        val byteDataSize = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + byteDataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // AudioFormat = PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // ByteRate
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(byteDataSize)
        }

        val pcmBuffer = ByteBuffer.allocate(byteDataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            pcmBuffer.putShort(s)
        }

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            fos.write(pcmBuffer.array())
        }
        return file
    }
}
