package com.rustvnt.remote;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.nio.ByteBuffer;

/** Small, explicit JNI ABI. Video buffers never cross this interface. */
public final class NativeBridge implements AutoCloseable {
    static { System.loadLibrary("vnt_remote_android"); }
    public interface Listener { void onEvent(String event, String payload); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile long handle;
    private Object surfaceOwner;
    public NativeBridge(String filesDirectory, int sdk, Listener callback) {
        listener = callback;
        handle = nativeCreate(this, filesDirectory, sdk);
    }
    public void onNativeEvent(String event, String payload) {
        main.post(() -> { Listener l = listener; if (handle != 0 && l != null) l.onEvent(event, payload); });
    }
    public synchronized void command(String json) { if (handle != 0) nativeCommand(handle, json); }
    public synchronized void surface(Object owner, Surface surface) {
        if (surface == null && owner != surfaceOwner) return;
        surfaceOwner = surface == null ? null : owner;
        if (handle != 0) nativeSurface(handle, surface);
    }
    public synchronized void input(long epoch, int stream, int generation, long revision, ByteBuffer buffer, int count) {
        if (handle != 0) nativeInput(handle, epoch, stream, generation, revision, buffer, count);
    }
    public int readAudio(ByteBuffer buffer) {
        long current = handle;
        if (current == 0) return 0;
        try { return nativeReadAudio(current, buffer); } catch (IllegalStateException ignored) { return 0; }
    }
    @Override public synchronized void close() {
        long current = handle; handle = 0; listener = null; main.removeCallbacksAndMessages(null);
        if (current != 0) nativeDestroy(current);
    }
    private static native long nativeCreate(Object callback, String filesDirectory, int sdk);
    private static native void nativeCommand(long handle, String json);
    private static native void nativeSurface(long handle, Surface surface);
    private static native void nativeInput(long handle, long epoch, int stream, int generation, long revision, ByteBuffer buffer, int count);
    private static native int nativeReadAudio(long handle, ByteBuffer buffer);
    private static native void nativeDestroy(long handle);
}
