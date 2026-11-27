package it.justapps.justchords.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The clock of a lesson: a count-in, then [totalBeats] at [bpm] with a click on
 * every beat (accent on the bar), and optionally plucked notes (riffs, "Ascolta").
 * [position] is the beat the speaker is playing now (negative during the count-in).
 */
class PracticeClock(
    private val bpm: Int,
    private val totalBeats: Double,
    private val bar: Int = 4,
    private val countIn: Int = bar,
    /** (beat, MIDI notes, beats long) to play as plucked strings. */
    private val notes: List<Triple<Double, List<Int>, Double>> = emptyList(),
    private val clicks: Boolean = true,
    private val onEnd: () -> Unit = {},
) {
    @Volatile var position = -countIn.toDouble()
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = 44100
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val spb = sr * 60.0 / bpm
            val high = click(sr, 1760.0, 0.5)
            val low = click(sr, 1100.0, 0.35)
            // Sounds already scheduled: (start sample, buffer).
            val events = ArrayList<Pair<Long, FloatArray>>()
            for (b in 0 until countIn + kotlin.math.ceil(totalBeats).toInt()) {
                if (clicks || b < countIn) events += (b * spb).toLong() to if (b % bar == 0) high else low
            }
            val start = (countIn * spb).toLong()
            for ((at, midi, beats) in notes) {
                val len = min(1.6, beats * 60.0 / bpm + 0.25)
                midi.forEach { m -> events += (start + (at * spb).toLong()) to pluck(sr, m, len) }
            }
            events.sortBy { it.first }
            val end = start + (totalBeats * spb).toLong()
            val block = FloatArray(512)
            var sample = 0L
            var next = 0
            val active = ArrayList<Pair<Long, FloatArray>>()
            track.play()
            while (running) {
                block.fill(0f)
                while (next < events.size && events[next].first < sample + block.size) active += events[next++]
                val it = active.iterator()
                while (it.hasNext()) {
                    val (at, buf) = it.next()
                    for (i in block.indices) {
                        val k = sample + i - at
                        if (k in 0 until buf.size) block[i] += buf[k.toInt()]
                    }
                    if (sample + block.size - at >= buf.size) it.remove()
                }
                for (i in block.indices) block[i] = block[i].coerceIn(-1f, 1f)
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                sample += block.size
                val heard = track.playbackHeadPosition.toLong()
                position = (heard - start) / spb
                if (heard >= end) break
            }
            track.stop()
            track.release()
            val finished = running
            running = false
            if (finished) onEnd()
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        fun click(sr: Int, hz: Double, gain: Double) = FloatArray((sr * 0.04).toInt()) { i ->
            val t = i.toDouble() / sr
            (sin(2 * PI * hz * t) * min(1.0, t / 0.002) * exp(-t * 110) * gain).toFloat()
        }

        /** Karplus-Strong plucked string. */
        fun pluck(sr: Int, midi: Int, seconds: Double): FloatArray {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val period = max(2, (sr / hz).toInt())
            val rnd = Random(midi)
            val ring = FloatArray(period) { rnd.nextFloat() * 2 - 1 }
            val out = FloatArray((sr * seconds).toInt())
            var p = 0
            for (i in out.indices) {
                val a = ring[p]
                val b = ring[(p + 1) % period]
                ring[p] = 0.996f * 0.5f * (a + b)
                val fade = if (i > out.size - sr * 0.03) (out.size - i) / (sr * 0.03).toFloat() else 1f
                out[i] = a * 0.45f * fade
                p = (p + 1) % period
            }
            return out
        }
    }
}
