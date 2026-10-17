package com.g12.sdk;

import com.skydroid.rcsdk.KeyManager;
import com.skydroid.rcsdk.key.RemoteControllerKey;
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith;
import com.skydroid.rcsdk.common.error.SkyException;

/** Original read-only integration seam, signature-checked against RCSDK 1.9.2.
 * Not part of the APK. Caller must initialize the SDK and verify G12 separately.
 * Receipt time is NOT a hardware sample time; output must never authorize control.
 */
public final class ReadOnlyChannels implements AutoCloseable {
    public interface Observer {
        void received(int[] rawChannels, long receiptMonotonicMillis, boolean sourceFreshnessVerified);
        void failed(String reason);
    }
    private long generation;
    private boolean closed, pending;

    public synchronized boolean requestOnce(Observer observer) {
        if (closed || pending) return false;
        if (observer == null) throw new IllegalArgumentException("Observer required");
        pending = true; final long epoch = ++generation;
        try {
            KeyManager.INSTANCE.get(RemoteControllerKey.INSTANCE.getKeyChannels(), new CompletionCallbackWith<int[]>() {
                @Override public void onSuccess(int[] value) {
                    synchronized (ReadOnlyChannels.this) {
                        if (closed || generation != epoch || !pending) return;
                        pending = false;
                        if (value == null || value.length == 0) observer.failed("Empty SDK channel result");
                        else observer.received(value.clone(), System.nanoTime() / 1_000_000L, false);
                    }
                }
                @Override public void onFailure(SkyException error) {
                    synchronized (ReadOnlyChannels.this) {
                        if (closed || generation != epoch || !pending) return;
                        pending = false; observer.failed("SDK channel request failed");
                    }
                }
            });
        } catch (RuntimeException error) {
            pending = false; generation++; observer.failed("SDK channel request threw"); return false;
        }
        return true;
    }
    /** Call on request timeout/disconnect. Drops callbacks; a new object is required to retry. */
    @Override public synchronized void close() { closed = true; pending = false; generation++; }
}
