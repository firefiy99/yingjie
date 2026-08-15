package com.yingjie.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** 用 Android 原生 MediaExtractor + MediaMuxer 合并音视频（无需 ffmpeg） */
object MuxerHelper {

    fun merge(videoPath: String, audioPath: String, outPath: String): Boolean {
        var muxer: MediaMuxer? = null
        try {
            val videoExtractor = MediaExtractor().apply { setDataSource(videoPath) }
            val audioExtractor = MediaExtractor().apply { setDataSource(audioPath) }

            val videoTrack = findTrack(videoExtractor, video = true)
            val audioTrack = findTrack(audioExtractor, video = false)
            if (videoTrack < 0 || audioTrack < 0) {
                videoExtractor.release()
                audioExtractor.release()
                return false
            }

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrackIndex = muxer.addTrack(videoFormat)
            val audioTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            videoExtractor.selectTrack(videoTrack)
            copySamples(videoExtractor, muxer, videoTrackIndex)
            videoExtractor.release()

            audioExtractor.selectTrack(audioTrack)
            copySamples(audioExtractor, muxer, audioTrackIndex)
            audioExtractor.release()

            muxer.stop()
            muxer.release()
            muxer = null
            return true
        } catch (e: Exception) {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            return false
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun findTrack(extractor: MediaExtractor, video: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (video && mime.startsWith("video/")) return i
            if (!video && mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val bufferSize = 1024 * 1024
        val buffer = java.nio.ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) break

            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            // 修正时间戳非单调递增问题
            if (bufferInfo.presentationTimeUs < 0) {
                bufferInfo.presentationTimeUs = 0
            }
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)

            if (!extractor.advance()) break
        }
    }
}
