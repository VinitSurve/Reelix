package app.reelix.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface AudioExtractionResult {
    data class Success(
        val audioFile: File,
        val sampleRate: Int = TARGET_SAMPLE_RATE,
        val channels: Int = 1,
        val durationMs: Long,
    ) : AudioExtractionResult

    data class NoAudioTrack(val message: String) : AudioExtractionResult
    data class CorruptedMedia(val message: String) : AudioExtractionResult
    data class InsufficientStorage(val message: String) : AudioExtractionResult
    data class Failure(val error: String) : AudioExtractionResult
}

const val TARGET_SAMPLE_RATE = 16000

interface AudioExtractor {
    suspend fun extractAudio(
        sourceMediaFile: File,
        outputWavFile: File,
    ): AudioExtractionResult
}

/**
 * Android framework-based audio extractor using [MediaExtractor] and [MediaCodec].
 * Decodes video and audio media into a transcription-standard 16,000 Hz 16-bit mono WAV.
 * Zero external libraries or APK bloat; 100% license-compatible and hardware-accelerated.
 */
class AndroidMediaCodecAudioExtractor : AudioExtractor {

    override suspend fun extractAudio(
        sourceMediaFile: File,
        outputWavFile: File,
    ): AudioExtractionResult = withContext(Dispatchers.IO) {
        if (!sourceMediaFile.exists() || sourceMediaFile.length() == 0L) {
            return@withContext AudioExtractionResult.CorruptedMedia("Source media file does not exist or is empty")
        }

        // Ensure available storage on device for the extracted audio
        if (outputWavFile.parentFile?.usableSpace ?: 0L < MIN_STORAGE_SPACE_BYTES) {
            return@withContext AudioExtractionResult.InsufficientStorage("Not enough disk space to extract audio")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val tempWav = File(outputWavFile.parentFile, "${outputWavFile.name}.tmp")

        try {
            try {
                extractor.setDataSource(sourceMediaFile.absolutePath)
            } catch (e: Exception) {
                return@withContext AudioExtractionResult.CorruptedMedia("Cannot read media container: ${e.message}")
            }

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime: String? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    audioMime = mime
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null || audioMime == null) {
                return@withContext AudioExtractionResult.NoAudioTrack("No audio track found in media file")
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val durationMs = durationUs / 1000L

            val sourceSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val sourceChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            try {
                codec = MediaCodec.createDecoderByType(audioMime)
            } catch (e: Exception) {
                return@withContext AudioExtractionResult.Failure("No codec available on device for '$audioMime': ${e.message}")
            }

            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false
            var totalPcmBytes = 0L

            FileOutputStream(tempWav).use { fos ->
                // Write placeholder 44-byte WAV header
                fos.write(ByteArray(44))

                val sampleResampler = SimplePcmResampler(
                    inSampleRate = sourceSampleRate,
                    inChannels = sourceChannels,
                    outSampleRate = TARGET_SAMPLE_RATE,
                )

                while (!isOutputEos) {
                    currentCoroutineContext().ensureActive()

                    // Feed input
                    if (!isInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isInputEos = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    // Read output
                    val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && info.size > 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)

                                val resampledBytes = sampleResampler.process(outputBuffer)
                                if (resampledBytes.isNotEmpty()) {
                                    fos.write(resampledBytes)
                                    totalPcmBytes += resampledBytes.size
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isOutputEos = true
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            val newSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                            val newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                            sampleResampler.updateSourceFormat(newSampleRate, newChannels)
                        }
                    }
                }
            }

            // Write finalized WAV header with actual PCM byte count
            writeWavHeader(tempWav, totalPcmBytes, TARGET_SAMPLE_RATE, 1, 16)

            if (outputWavFile.exists()) outputWavFile.delete()
            if (!tempWav.renameTo(outputWavFile)) {
                tempWav.copyTo(outputWavFile, overwrite = true)
                tempWav.delete()
            }

            AudioExtractionResult.Success(
                audioFile = outputWavFile,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = 1,
                durationMs = durationMs,
            )
        } catch (e: Exception) {
            tempWav.delete()
            ReelixLog.w("Audio extraction failed", e)
            AudioExtractionResult.Failure("Extraction failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            val byteRate = (sampleRate * channels * bitsPerSample) / 8
            val blockAlign = (channels * bitsPerSample) / 8
            val totalDataLen = pcmDataLength + 36

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen.toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1) // AudioFormat: 1 = PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(pcmDataLength.toInt())

            raf.write(header.array())
        }
    }

    companion object {
        private const val TIMEOUT_US = 5000L
        private const val MIN_STORAGE_SPACE_BYTES = 20L * 1024 * 1024 // 20MB
    }
}

/**
 * Linear resampler and mono downmixer for 16-bit PCM.
 * Transforms varying input formats to 16,000 Hz 16-bit mono PCM.
 */
class SimplePcmResampler(
    private var inSampleRate: Int,
    private var inChannels: Int,
    private val outSampleRate: Int = TARGET_SAMPLE_RATE,
) {
    fun updateSourceFormat(sampleRate: Int, channels: Int) {
        inSampleRate = sampleRate
        inChannels = channels
    }

    fun process(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSourceSamples = shortBuffer.remaining()
        val totalSourceFrames = totalSourceSamples / inChannels.coerceAtLeast(1)

        if (totalSourceFrames == 0) return ByteArray(0)

        // Downmix to mono shorts
        val monoSource = ShortArray(totalSourceFrames)
        for (i in 0 until totalSourceFrames) {
            var sum = 0
            for (c in 0 until inChannels) {
                sum += shortBuffer.get()
            }
            monoSource[i] = (sum / inChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Resample from inSampleRate to outSampleRate
        if (inSampleRate == outSampleRate) {
            val outBytes = ByteArray(monoSource.size * 2)
            ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoSource)
            return outBytes
        }

        val outFrames = ((totalSourceFrames.toDouble() * outSampleRate) / inSampleRate).toInt().coerceAtLeast(1)
        val outShorts = ShortArray(outFrames)

        val ratio = inSampleRate.toDouble() / outSampleRate.toDouble()
        for (i in 0 until outFrames) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, totalSourceFrames - 1)
            outShorts[i] = monoSource[srcIdx]
        }

        val outBytes = ByteArray(outShorts.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        return outBytes
    }
}

