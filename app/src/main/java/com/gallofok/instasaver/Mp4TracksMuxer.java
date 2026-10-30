package com.gallofok.instasaver;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/**
 * Copies compressed AVC/HEVC video and AAC audio samples into an MP4 container.
 * Does not re-encode, need FFmpeg, or combine incompatible codecs.
 */
final class Mp4TracksMuxer {
    private static final int BUFFER_BYTES = 4 * 1024 * 1024;

    private Mp4TracksMuxer() {}

    static void merge(File videoFile, File audioFile, File output,
                      BooleanSupplier cancelled) throws IOException {
        if (!videoFile.isFile() || !audioFile.isFile()) {
            throw new IOException("Missing separate video or audio track");
        }

        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        boolean completed = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());
            int inputVideo = findTrack(video, "video/");
            int inputAudio = findTrack(audio, "audio/");
            if (inputVideo < 0 || inputAudio < 0) {
                throw new IOException("Missing MP4 video or M4A audio track");
            }
            MediaFormat videoFormat = video.getTrackFormat(inputVideo);
            MediaFormat audioFormat = audio.getTrackFormat(inputAudio);
            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);
            String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (!(("video/avc".equals(videoMime) || "video/hevc".equals(videoMime))
                    && "audio/mp4a-latm".equals(audioMime))) {
                throw new IOException("Unsupported Android MP4 remux codecs: "
                        + videoMime + " + " + audioMime);
            }

            video.selectTrack(inputVideo);
            audio.selectTrack(inputAudio);
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputVideo = muxer.addTrack(videoFormat);
            int outputAudio = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            copySamples(video, muxer, outputVideo, cancelled);
            copySamples(audio, muxer, outputAudio, cancelled);
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled by user");
            completed = true;
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } finally {
                    muxer.release();
                }
            }
            video.release();
            audio.release();
            if (!completed && output.exists()) output.delete();
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); ++i) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copySamples(MediaExtractor extractor, MediaMuxer muxer,
                                    int outTrack, BooleanSupplier cancelled) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocateDirect(BUFFER_BYTES);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled by user");
            bytes.clear();
            int length = extractor.readSampleData(bytes, 0);
            if (length < 0) break;
            long timestampUs = extractor.getSampleTime();
            if (timestampUs < 0) break;
            info.set(0, length, timestampUs, extractor.getSampleFlags());
            bytes.position(0);
            bytes.limit(length);
            muxer.writeSampleData(outTrack, bytes, info);
            extractor.advance();
        }
    }
}
