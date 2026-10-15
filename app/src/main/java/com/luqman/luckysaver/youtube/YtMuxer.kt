package com.luqman.luckysaver.youtube

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Joins YouTube's separate video and audio files into one MP4 with Android's own muxer: no
 * FFmpeg, no re-encoding, so a 1080p video takes seconds. Also used with a single audio file, to
 * turn YouTube's fragmented M4A into a plain one that every music player reads the length of.
 */
object YtMuxer {

    private class Track(val extractor: MediaExtractor, val muxerIndex: Int) {
        var done = false
    }

    fun mux(inputs: List<File>, out: FileDescriptor, isStopped: () -> Boolean = { false }) {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tracks = ArrayList<Track>()
        try {
            var maxSample = 1 shl 20
            inputs.forEach { file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.path)
                val index = (0 until extractor.trackCount).firstOrNull { i ->
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("video/") || mime.startsWith("audio/")
                } ?: run {
                    extractor.release()
                    error("No audio or video track in ${file.name}")
                }
                extractor.selectTrack(index)
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxSample = maxOf(maxSample, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                tracks += Track(extractor, muxer.addTrack(format))
            }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(maxOf(maxSample, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            // Interleaved by time: writing one whole track and then the other makes the muxer hold
            // the first in memory, which a long video doesn't fit.
            while (true) {
                if (isStopped()) throw InterruptedException("Muxing stopped")
                val next = tracks.filter { !it.done }.minByOrNull { it.extractor.sampleTime } ?: break
                buffer.clear()
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.done = true
                    continue
                }
                val keyFrame = next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, next.extractor.sampleTime, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(next.muxerIndex, buffer, info)
                if (!next.extractor.advance()) next.done = true
            }
            muxer.stop()
        } finally {
            tracks.forEach { runCatching { it.extractor.release() } }
            runCatching { muxer.release() }
        }
    }
}
