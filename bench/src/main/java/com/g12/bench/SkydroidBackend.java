package com.g12.bench;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.skydroid.rcsdk.KeyManager;
import com.skydroid.rcsdk.RCSDKManager;
import com.skydroid.rcsdk.SDKManagerCallBack;
import com.skydroid.rcsdk.common.DeviceType;
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith;
import com.skydroid.rcsdk.common.callback.KeyListener;
import com.skydroid.rcsdk.common.error.SkyException;
import com.skydroid.rcsdk.key.AirLinkKey;
import com.skydroid.rcsdk.key.RemoteControllerKey;

/** Real RCSDK 1.9.2. Only init/connect/disconnect, GET, LISTEN and cancelListen are exposed.
 * No PipelineManager, KeyManager.set/action, MAVLink codec, camera or upgrade APIs.
 */
public final class SkydroidBackend implements ReadOnlySession.Backend {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ReadOnlySession.Events events;
    private KeyListener<String> rawListener;
    private KeyListener<Integer> qualityListener;
    private boolean initialized;

    public SkydroidBackend(Context context) { this.context = context.getApplicationContext(); }
    @Override public void start(ReadOnlySession.Events next) {
        if (events != null) throw new IllegalStateException("SDK session already active");
        events = next;
        if (!initialized) {
            RCSDKManager.INSTANCE.initSDK(context, new SDKManagerCallBack() {
                @Override public void onRcConnected() {
                    ReadOnlySession.Events target = events;
                    main.post(() -> {
                        if (target == null || events != target) return;
                        DeviceType type = RCSDKManager.INSTANCE.getDeviceType();
                        target.connected(type == null ? "UNKNOWN" : type.name());
                        if (events == target && type == DeviceType.G12) subscribe(target);
                    });
                }
                @Override public void onRcConnectFail(SkyException error) { disconnected("SDK 连接失败: " + describe(error)); }
                @Override public void onRcDisconnect() { disconnected("SDK 已断开，采集停止；请手动重试"); }
            });
            initialized = true;
            RCSDKManager.INSTANCE.setMainThreadCallBack(true);
            RCSDKManager.INSTANCE.setDebug(false);
            RCSDKManager.INSTANCE.setLogEnable(false);
        }
        RCSDKManager.INSTANCE.connectToRC();
    }
    private void disconnected(String reason) {
        ReadOnlySession.Events target = events;
        main.post(() -> { if (target != null && events == target) target.disconnected(reason); });
    }
    private void subscribe(ReadOnlySession.Events target) {
        if (rawListener != null || qualityListener != null) return;
        rawListener = (previous, value) -> main.post(() -> { if (events == target) target.signal(value); });
        qualityListener = (previous, value) -> main.post(() -> { if (events == target) target.quality(value); });
        try {
            SkyException rawError = KeyManager.INSTANCE.listen(AirLinkKey.INSTANCE.getKeyRawSignalQuality(), rawListener);
            SkyException qualityError = KeyManager.INSTANCE.listen(AirLinkKey.INSTANCE.getKeySignalQuality(), qualityListener);
            if (rawError != null || qualityError != null) target.warning("链路监听未完全建立: " + describe(rawError) + " / " + describe(qualityError));
        } catch (RuntimeException | LinkageError e) { target.warning("链路监听异常: " + e.getClass().getSimpleName()); }
    }
    @Override public void read(ReadOnlySession.Result result) {
        KeyManager.INSTANCE.get(RemoteControllerKey.INSTANCE.getKeyChannels(), new CompletionCallbackWith<int[]>() {
            @Override public void onSuccess(int[] values) {
                // Vendor may reuse the buffer; copy before asynchronous dispatch.
                int[] copy = values == null ? null : values.clone();
                main.post(() -> result.success(copy));
            }
            @Override public void onFailure(SkyException error) { main.post(() -> result.failure(describe(error))); }
        });
    }
    @Override public void stop() {
        events = null;
        try {
            if (rawListener != null) KeyManager.INSTANCE.cancelListen(rawListener);
        } finally {
            rawListener = null;
            try {
                if (qualityListener != null) KeyManager.INSTANCE.cancelListen(qualityListener);
            } finally {
                qualityListener = null;
                if (initialized) RCSDKManager.INSTANCE.disconnectRC();
            }
        }
    }
    private static String describe(SkyException e) { return e == null ? "无错误" : "code=" + e.getErrorCode() + " " + e.getMessage(); }
}
