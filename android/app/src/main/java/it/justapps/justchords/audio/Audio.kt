package it.justapps.justchords.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Microphone into a ring buffer; read() gives the latest n samples. Audio never leaves the phone. */
class Mic private constructor(private val rec: AudioRecord, val sampleRate: Int, size: Int) {
    private val ring = FloatArray(size)
    private var pos = 0
    @Volatile private var running = true
    private val thread = Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(1024)
        rec.startRecording()
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            synchronized(ring) {
                for (i in 0 until n) {
                    ring[pos] = buf[i] / 32768f
                    pos = (pos + 1) % ring.size
                }
            }
        }
    }.apply { start() }

    fun read(n: Int): FloatArray = synchronized(ring) {
        val out = FloatArray(n)
        var p = (pos - n + ring.size * 2) % ring.size
        for (i in 0 until n) {
            out[i] = ring[p]
            p = (p + 1) % ring.size
        }
        out
    }

    fun stop() {
        running = false
        runCatching { thread.join(300) }
        runCatching { rec.stop() }
        rec.release()
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun open(size: Int = 16384): Mic {
            for (sr in intArrayOf(44100, 48000, 22050, 16000)) {
                val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                // UNPROCESSED/raw where available: no noise suppression eating the guitar.
                val rec = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
                }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?: AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
                if (rec.state == AudioRecord.STATE_INITIALIZED) return Mic(rec, sr, size)
                rec.release()
            }
            error("microphone not available")
        }
    }
}

/** Sample-accurate metronome: clicks are written into the audio stream (metronome.ts). */
class Metronome {
    data class Config(val bpm: Int, val beats: Int, val sub: Int, val accent: Boolean = true)

    @Volatile var config = Config(100, 4, 1)
    /** Beat being heard now, -1 when stopped. */
    @Volatile var beat = -1
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = 48000
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val clicks = arrayOf(click(sr, 760.0, 0.25), click(sr, 1100.0, 0.6), click(sr, 1760.0, 0.6))
            val block = FloatArray(512)
            var sample = 0L
            var nextTick = (sr * 0.05).toLong()
            var tick = 0
            var playing: FloatArray? = null
            var playPos = 0
            val pending = ArrayDeque<Pair<Long, Int>>()
            track.play()
            while (running) {
                val c = config
                val per = sr * 60.0 / c.bpm / c.sub
                block.fill(0f)
                for (i in block.indices) {
                    val now = sample + i
                    if (now >= nextTick) {
                        val total = c.beats * c.sub
                        val k = tick % total
                        val b = k / c.sub
                        val s = k % c.sub
                        playing = clicks[if (s != 0) 0 else if (b == 0 && c.accent) 2 else 1]
                        playPos = 0
                        if (s == 0) pending.addLast(now to b)
                        tick++
                        nextTick = now + per.toLong()
                    }
                    playing?.let { p ->
                        block[i] = p[playPos++]
                        if (playPos >= p.size) playing = null
                    }
                }
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                sample += block.size
                // What the speaker plays now = written minus what is still buffered.
                val heard = track.playbackHeadPosition.toLong()
                while (pending.isNotEmpty() && pending.first().first <= heard) beat = pending.removeFirst().second
            }
            track.stop()
            track.release()
            beat = -1
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        beat = -1
    }

    private fun click(sr: Int, hz: Double, gain: Double) = FloatArray((sr * 0.05).toInt()) { i ->
        val t = i.toDouble() / sr
        val env = min(1.0, t / 0.002) * exp(-t * 90)
        (sin(2 * PI * hz * t) * env * gain).toFloat()
    }

    companion object {
        /** Tap tempo: average of the last taps (ms timestamps). */
        fun tapTempo(taps: List<Long>): Int? {
            val recent = taps.filterIndexed { i, t -> i == 0 || t - taps[i - 1] < 2000 }.takeLast(6)
            if (recent.size < 2) return null
            val gaps = recent.zipWithNext { a, b -> b - a }
            return Math.round(60000.0 / gaps.average()).toInt()
        }
    }
}

/** Any audio or video file → mono samples, with MediaCodec (decodeAudioData on the web). */
object Decoder {
    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    suspend fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 600, onProgress: (Float) -> Unit = {}): Pcm = withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("no audio track")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var out = FloatArray(sr * 60)
        var n = 0
        var float = false
        val info = MediaCodec.BufferInfo()
        var inDone = false
        try {
            while (coroutineContext.isActive) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            if (durUs > 0) onProgress(min(1f, ex.sampleTime.toFloat() / durUs) * 0.3f)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = if (float) info.size / 4 / ch else info.size / 2 / ch
                    if (n + frames > out.size) out = out.copyOf(max(out.size * 2, n + frames))
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); out[n++] = s / ch }
                    } else {
                        val sb = buf.asShortBuffer()
                        for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get() / 32768f; out[n++] = s / ch }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n > sr * maxSeconds) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
        Pcm(out.copyOf(n), sr)
    }
}
