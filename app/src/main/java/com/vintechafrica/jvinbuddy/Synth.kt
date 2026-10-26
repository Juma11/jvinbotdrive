package com.vintechafrica.jvinbuddy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A tiny offline music studio. Every drum and note is synthesised from maths,
 * so there are no sound files and no copyright: every beat is VinBot's own.
 */
object Synth {
    const val RATE = 22050

    // ---------------------------------------------------------------- instruments
    private fun len(sec: Double) = (RATE * sec).toInt()

    fun kick(): FloatArray {
        val n = len(0.35); var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = 48 + 110 * exp(-t * 32)
            phase += 2 * PI * f / RATE
            (sin(phase) * exp(-t * 9)).toFloat()
        }
    }

    fun snare(): FloatArray {
        val n = len(0.2)
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            ((Random.nextDouble(-1.0, 1.0) * 0.55 * exp(-t * 22)) + sin(2 * PI * 185 * t) * 0.4 * exp(-t * 25)).toFloat()
        }
    }

    fun rim(): FloatArray {
        val n = len(0.06)
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            (sin(2 * PI * 820 * t) * exp(-t * 70) * 0.6).toFloat()
        }
    }

    fun hat(open: Boolean = false): FloatArray {
        val n = len(if (open) 0.18 else 0.05); var prev = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val x = Random.nextDouble(-1.0, 1.0)
            val hp = x - prev; prev = x
            (hp * 0.3 * exp(-t * if (open) 18 else 85)).toFloat()
        }
    }

    fun shaker(): FloatArray {
        val n = len(0.07); var prev = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val x = Random.nextDouble(-1.0, 1.0)
            val hp = x - prev; prev = x
            val env = if (t < 0.015) t / 0.015 else exp(-(t - 0.015) * 55)
            (hp * 0.16 * env).toFloat()
        }
    }

    private val clapStarts = doubleArrayOf(0.0, 0.011, 0.022)

    fun clap(): FloatArray {
        val n = len(0.22)
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            var burst = 0.0
            for (st in clapStarts) if (t >= st) burst += exp(-(t - st) * 140)
            val tail = if (t > 0.022) exp(-(t - 0.022) * 18) * 0.5 else 0.0
            (Random.nextDouble(-1.0, 1.0) * (burst * 0.5 + tail) * 0.55).toFloat()
        }
    }

    /** Amapiano log drum: a deep, slightly distorted, gliding bass hit. */
    fun logDrum(freq: Double): FloatArray {
        val n = len(0.42); var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = freq * (1 + 0.6 * exp(-t * 28))
            phase += 2 * PI * f / RATE
            (tanh(sin(phase) * 2.2) * 0.75 * exp(-t * 5.5)).toFloat()
        }
    }

    fun bass(freq: Double, sec: Double): FloatArray {
        val n = len(sec); var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            phase += 2 * PI * freq / RATE
            val env = minOf(1.0, t / 0.008) * minOf(1.0, (sec - t) / 0.04)
            ((sin(phase) + 0.25 * sin(phase * 2)) * 0.55 * env).toFloat()
        }
    }

    /** Bright chiptune lead for melodies. */
    fun lead(freq: Double, sec: Double): FloatArray {
        val n = len(sec); var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            phase += 2 * PI * freq * (1 + 0.004 * sin(2 * PI * 5.5 * t)) / RATE
            val sq = if (sin(phase) >= 0) 1.0 else -1.0
            val env = minOf(1.0, t / 0.01) * minOf(1.0, (sec - t) / 0.06) * (0.75 + 0.25 * exp(-t * 6))
            ((sq * 0.18 + sin(phase) * 0.22) * env).toFloat()
        }
    }

    // ---------------------------------------------------------------- mixing
    fun mix(into: FloatArray, sample: FloatArray, at: Int, gain: Float) {
        for (i in sample.indices) {
            val j = at + i
            if (j >= into.size) return
            if (j >= 0) into[j] += sample[i] * gain
        }
    }

    fun toPcm(buf: FloatArray, volume: Float = 0.9f): ShortArray =
        ShortArray(buf.size) { (tanh(buf[it] * volume.toDouble()) * 32000).toInt().toShort() }

    // ---------------------------------------------------------------- beats
    enum class Style(val bpm: Int, val spoken: String) {
        AMAPIANO(112, "amapiano"),
        AFROBEAT(104, "afrobeat"),
        HIPHOP(90, "hip hop"),
        HOUSE(122, "house"),
        DANCEHALL(98, "dancehall");

        companion object {
            fun from(text: String): Style? = when {
                "piano" in text || "amapiano" in text -> AMAPIANO
                "afro" in text -> AFROBEAT
                "hip" in text || "rap" in text -> HIPHOP
                "house" in text -> HOUSE
                "dancehall" in text || "reggae" in text -> DANCEHALL
                else -> null
            }
        }
    }

    // Chord roots per bar (A minor-ish loop that suits most styles)
    private val roots = doubleArrayOf(55.0, 43.65, 65.41, 49.0) // A1, F1, C2, G1

    /** Renders [bars] bars of a beat. Bar 4 gets a little fill. */
    fun renderBeat(style: Style, bars: Int = 4): FloatArray {
        val stepSec = 60.0 / style.bpm / 4
        val step = (RATE * stepSec).toInt()
        val buf = FloatArray(step * 16 * bars)
        val k = kick(); val s = snare(); val c = clap(); val h = hat(); val ho = hat(true)
        val sh = shaker(); val r = rim()
        for (bar in 0 until bars) {
            val root = roots[bar % roots.size]
            fun at(st: Int) = (bar * 16 + st) * step
            fun hit(sample: FloatArray, steps: IntArray, gain: Float) = steps.forEach { mix(buf, sample, at(it), gain) }
            val fill = bar == bars - 1
            when (style) {
                Style.AMAPIANO -> {
                    hit(k, intArrayOf(0, 4, 8, 12), 0.9f)
                    hit(c, intArrayOf(4, 12), 0.45f)
                    hit(sh, IntArray(16) { it }, 1f)
                    hit(h, intArrayOf(2, 6, 10, 14), 0.8f)
                    val ld = intArrayOf(3, 6, 10, 14)
                    val mult = doubleArrayOf(1.0, 1.0, 1.19, 1.33)
                    ld.forEachIndexed { i, st -> mix(buf, logDrum(root * 2 * mult[i]), at(st), 0.8f) }
                    if (fill) hit(r, intArrayOf(13, 14, 15), 0.6f)
                }
                Style.AFROBEAT -> {
                    hit(k, intArrayOf(0, 7, 8), 0.9f)
                    hit(c, intArrayOf(4, 12), 0.55f)
                    hit(r, intArrayOf(3, 6, 10, 13), 0.5f)
                    hit(h, IntArray(8) { it * 2 }, 0.8f)
                    hit(sh, IntArray(16) { it }, 0.7f)
                    intArrayOf(0, 6, 10).forEach { mix(buf, bass(root * 2, stepSec * 3), at(it), 0.7f) }
                    if (fill) hit(s, intArrayOf(13, 14, 15), 0.5f)
                }
                Style.HIPHOP -> {
                    hit(k, intArrayOf(0, 7, 10), 1f)
                    hit(s, intArrayOf(4, 12), 0.75f)
                    hit(h, IntArray(8) { it * 2 }, 0.9f)
                    hit(ho, intArrayOf(14), 0.5f)
                    intArrayOf(0, 10).forEach { mix(buf, bass(root * 2, stepSec * 5), at(it), 0.8f) }
                    if (fill) hit(s, intArrayOf(14, 15), 0.5f)
                }
                Style.HOUSE -> {
                    hit(k, intArrayOf(0, 4, 8, 12), 1f)
                    hit(ho, intArrayOf(2, 6, 10, 14), 0.7f)
                    hit(h, IntArray(16) { it }, 0.35f)
                    hit(c, intArrayOf(4, 12), 0.6f)
                    intArrayOf(2, 6, 10, 14).forEach { mix(buf, bass(root * 2, stepSec * 1.5), at(it), 0.7f) }
                }
                Style.DANCEHALL -> {
                    hit(k, intArrayOf(0, 8), 1f)
                    hit(s, intArrayOf(3, 6, 11, 14), 0.6f)
                    hit(h, IntArray(8) { it * 2 }, 0.7f)
                    intArrayOf(0, 3, 8, 11).forEach { mix(buf, bass(root * 2, stepSec * 2), at(it), 0.65f) }
                }
            }
        }
        return buf
    }

    /** Melody (one note per beat, last note of each line held) over a light beat. */
    fun renderSong(song: Song): Pair<FloatArray, List<Long>> {
        val beat = 60.0 / song.bpm
        val step = (RATE * beat / 4).toInt()
        val lineBeats = song.melody.map { it.size + 1 }
        val totalBeats = lineBeats.sum() + 2
        val buf = FloatArray(step * 4 * totalBeats)
        val starts = mutableListOf<Long>()
        var b = 1 // one beat count-in
        val k = kick(); val h = hat()
        song.melody.forEachIndexed { li, notes ->
            starts += (b * beat * 1000).toLong()
            notes.forEachIndexed { ni, ratio ->
                val held = if (ni == notes.lastIndex) 2.0 else 0.95
                mix(buf, lead(330.0 * ratio, beat * held), b * 4 * step, 0.9f)
                b++
            }
            b += lineBeats[li] - notes.size
        }
        // Light drums under the tune
        for (beatIdx in 0 until totalBeats) {
            if (beatIdx % 2 == 0) mix(buf, k, beatIdx * 4 * step, 0.5f)
            mix(buf, h, (beatIdx * 4 + 2) * step, 0.5f)
        }
        return buf to starts
    }

    /**
     * Plays PCM once (streamed) or on loop (static buffer, beats are short).
     * Call stop() and release() on the returned player.
     */
    fun play(pcm: ShortArray, loop: Boolean): AudioTrack {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        if (loop) {
            val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, pcm.size, -1)
            track.play()
            return track
        }
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, RATE)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        Thread {
            var off = 0
            try {
                while (off < pcm.size && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val n = track.write(pcm, off, minOf(4096, pcm.size - off))
                    if (n <= 0) break
                    off += n
                }
            } catch (_: Exception) {
            }
        }.start()
        return track
    }
}
