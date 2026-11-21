package com.example.grandfatherclock.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCapture(
    private val onBuffer: (ShortArray, Int) -> Unit,
    /** Called on the capture thread when recording fails; capture has ended by then. */
    private val onError: (Exception) -> Unit = {},
    private val wavOutputDir: File? = null,
    /**
     * For development: a 44.1 kHz mono 16-bit WAV to play through the pipeline
     * in place of the microphone, at [replaySpeed] times real time. Reaching
     * its end is reported through [onError].
     */
    private val replayWav: File? = null,
    private val replaySpeed: Double = 1.0,
) {
    companion object {
        const val SAMPLE_RATE = 44100

        /**
         * Phone clocks stay within a few tens of ppm, so a larger offset between
         * the measured and nominal sample rate means the audio timing itself is
         * unreliable (resampling, dropped samples). 50 ppm is about 30 s/week.
         */
        const val SUSPECT_RATE_OFFSET_PPM = 50.0

        /**
         * Audio needed before the measured rate offset is trusted. The offset is
         * measured from a timestamp taken about a second into the recording, and
         * over a minute even a 1 ms error in that timestamp stays below 17 ppm.
         */
        const val RATE_OFFSET_SETTLE_SECONDS = 60.0
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var recording = false
    private var thread: Thread? = null
    var totalSamplesRead: Long = 0L
        private set
    @Volatile
    var wavFile: File? = null
        private set

    /**
     * Actual sample rate measured via AudioTimestamp (system clock vs audio clock).
     * Falls back to [SAMPLE_RATE] until enough data is collected.
     */
    @Volatile
    var correctedSampleRate: Double = SAMPLE_RATE.toDouble()
        private set

    fun start() {
        totalSamplesRead = 0L
        correctedSampleRate = SAMPLE_RATE.toDouble()
        recording = true
        thread = Thread(::captureLoop, "AudioCapture")
        thread?.start()
    }

    fun stop() {
        recording = false
        thread?.join(2000)
        thread = null
    }

    /**
     * Runs on the capture thread. All setup happens here too, so that every
     * failure (microphone unavailable, read error, storage full) ends the
     * capture and reaches [onError] instead of crashing the process.
     */
    private fun replayLoop(source: File) {
        // The source stands in for the recording.
        wavFile = source
        try {
            RandomAccessFile(source, "r").use { input ->
                input.seek(44)
                val chunk = 882
                val bytes = ByteArray(chunk * 2)
                val buffer = ShortArray(chunk)
                val start = System.nanoTime()
                while (recording) {
                    val n = input.read(bytes)
                    if (n <= 0) throw IllegalStateException("Replay finished")
                    val samples = n / 2
                    ByteBuffer.wrap(bytes, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(buffer, 0, samples)
                    onBuffer(buffer, samples)
                    totalSamplesRead += samples
                    val dueNanos = (totalSamplesRead * 1e9 / SAMPLE_RATE / replaySpeed).toLong()
                    val wait = dueNanos - (System.nanoTime() - start)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            }
        } catch (e: Exception) {
            recording = false
            onError(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        replayWav?.let { replayLoop(it); return }
        var audioRecord: AudioRecord? = null
        var raf: RandomAccessFile? = null
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufferSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200ms worth of samples
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
            audioRecord = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone is unavailable")
            }

            // Set up WAV file if an output dir is provided
            raf = wavOutputDir?.let { dir ->
                dir.mkdirs()
                val file = File(dir, "clock_recording.wav")
                wavFile = file
                val r = RandomAccessFile(file, "rw")
                r.setLength(0)
                writeWavHeader(r, 0) // placeholder header, patched on stop
                r
            }

            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone did not start recording")
            }

            val readSize = 882 // ~20ms at 44100 Hz — small reads for low-latency detection
            val buffer = ShortArray(readSize)
            val byteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

            // AudioTimestamp tracking for crystal rate correction
            val ts = AudioTimestamp()
            var anchorFrame = Long.MIN_VALUE
            var anchorNanos = 0L
            var nextTimestampSamples = SAMPLE_RATE.toLong() // first check after ~1s

            while (recording) {
                val read = rec.read(buffer, 0, buffer.size)
                // A negative result is an error code (e.g. the audio server died);
                // retrying would spin without ever getting data.
                if (read < 0) throw IllegalStateException("Microphone read failed (error $read)")
                if (read == 0) continue

                onBuffer(buffer, read)
                totalSamplesRead += read

                // Periodically measure actual sample rate via AudioTimestamp
                if (totalSamplesRead >= nextTimestampSamples) {
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        if (anchorFrame == Long.MIN_VALUE) {
                            anchorFrame = ts.framePosition
                            anchorNanos = ts.nanoTime
                        } else {
                            val dFrames = ts.framePosition - anchorFrame
                            val dNanos = ts.nanoTime - anchorNanos
                            if (dFrames > 0 && dNanos > 0) {
                                correctedSampleRate =
                                    dFrames.toDouble() / dNanos * 1_000_000_000.0
                            }
                        }
                    }
                    nextTimestampSamples = totalSamplesRead + SAMPLE_RATE * 5L // re-check every ~5s
                }

                // Write to WAV
                raf?.let { r ->
                    byteBuffer.clear()
                    for (i in 0 until read) {
                        byteBuffer.putShort(buffer[i])
                    }
                    r.write(byteBuffer.array(), 0, read * 2)
                }
            }
        } catch (e: Exception) {
            recording = false
            onError(e)
        } finally {
            // Finalize WAV: patch header with actual data size
            raf?.let { r ->
                try {
                    r.seek(0)
                    writeWavHeader(r, totalSamplesRead * 2)
                } catch (_: Exception) {
                } finally {
                    try { r.close() } catch (_: Exception) {}
                }
            }
            audioRecord?.let { rec ->
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataSize: Long) {
        val totalSize = 36 + dataSize
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalSize.toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)            // subchunk1 size
        header.putShort(1)           // PCM format
        header.putShort(1)           // mono
        header.putInt(SAMPLE_RATE)   // sample rate
        header.putInt(SAMPLE_RATE * 2) // byte rate (sampleRate * channels * bitsPerSample/8)
        header.putShort(2)           // block align (channels * bitsPerSample/8)
        header.putShort(16)          // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        raf.write(header.array())
    }
}
