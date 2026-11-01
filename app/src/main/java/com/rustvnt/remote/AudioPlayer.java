package com.rustvnt.remote;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** One owner thread and one direct 10ms PCM buffer. No allocations in the playback loop. */
public final class AudioPlayer implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AudioTrack track;
    private Thread worker;
    public synchronized void start(NativeBridge bridge, java.util.function.Consumer<String> failure) {
        if (running.get()) return;
        running.set(true);
        worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioTrack output = null;
            try {
                int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
                if (minimum <= 0) throw new IllegalStateException("系统不支持 48kHz 立体声播放");
                output = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 3840 * 3)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack 初始化失败");
                track = output;
                ByteBuffer buffer = ByteBuffer.allocateDirect(3840).order(ByteOrder.nativeOrder());
                output.play();
                Log.i("VntAudio", "AudioTrack started: 48000 Hz stereo float");
                boolean receivedPcm = false;
                long waitingSince = System.nanoTime();
                boolean reportedWait = false;
                while (running.get()) {
                    buffer.clear();
                    int bytes = bridge.readAudio(buffer);
                    if (bytes == 0) {
                        if (!receivedPcm && !reportedWait && System.nanoTime() - waitingSince >= 5_000_000_000L) {
                            Log.w("VntAudio", "AudioTrack ready but no decoded PCM received for 5 seconds");
                            reportedWait = true;
                        }
                        LockSupport.parkNanos(2_000_000); continue;
                    }
                    if (!receivedPcm) { Log.i("VntAudio", "First decoded PCM received: " + bytes + " bytes"); receivedPcm = true; }
                    buffer.limit(bytes);
                    while (running.get() && buffer.hasRemaining()) {
                        int wrote = output.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                        if (wrote < 0) throw new IllegalStateException("音频输出失败: " + wrote);
                        if (wrote == 0) LockSupport.parkNanos(1_000_000);
                    }
                }
            } catch (RuntimeException e) {
                Log.e("VntAudio", "Local audio playback failed", e);
                if (running.get()) failure.accept(e.getMessage() == null ? "音频设备不可用" : e.getMessage());
            } finally {
                running.set(false); track = null;
                if (output != null) { try { output.pause(); output.flush(); } catch (RuntimeException ignored) {} output.release(); }
            }
        }, "vnt-audiotrack");
        worker.start();
    }
    @Override public synchronized void close() {
        running.set(false);
        Thread thread = worker; worker = null;
        if (thread != null) {
            LockSupport.unpark(thread);
            try { thread.join(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
