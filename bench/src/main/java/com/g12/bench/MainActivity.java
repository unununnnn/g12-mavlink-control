package com.g12.bench;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ReadOnlySession session;
    private TextView status, channels, link, detail;
    private Button start, stop;
    private final Runnable tick = new Runnable() {
        @Override public void run() { session.tick(); render(); main.postDelayed(this, 50); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = new ReadOnlySession(((BenchApplication) getApplication()).backend(), SystemClock::elapsedRealtime);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(24)); body.setBackgroundColor(Color.rgb(245,248,252));
        scroll.addView(body); setContentView(scroll);
        TextView title = text(body, "G12 · 真实 SDK 台架只读", 25); title.setTypeface(null, Typeface.BOLD);
        text(body, "RCSDK 1.9.2  /  v0.2.1-sdk-readonly\n只读采集 · 无飞控控制发送 · 非完整遥控版", 16).setTextColor(Color.rgb(145,63,0));
        text(body, "手动开始前，请关闭占用 SDK 端口的助手/地面站。首次仅给 G12 供电、断开接收机至飞控连线；链路观察可再接 GR00，仍保持飞控断开。SDK 会建立自身设备通信。", 15);
        LinearLayout buttons = new LinearLayout(this); body.addView(buttons);
        start = new Button(this); start.setText("开始真实只读采集"); buttons.addView(start);
        stop = new Button(this); stop.setText("停止采集"); buttons.addView(stop);
        start.setOnClickListener(v -> { session.start(); render(); });
        stop.setOnClickListener(v -> { session.pause("采集已停止；SDK连接保留，可再次开始读取"); render(); });
        Button release = new Button(this); release.setText("断开SDK / 释放端口"); body.addView(release);
        release.setOnClickListener(v -> { session.stop("SDK端口已请求释放"); render(); });
        text(body, "停止采集保留前台SDK连接，再次开始重新读取通道。切换其他App前释放端口；退到后台也会释放。", 15);
        status = text(body, "", 18);
        text(body, "原始通道  /  CH 编号是 SDK 数组顺序，未假定轴映射或量程", 16);
        channels = text(body, "", 20); channels.setTypeface(Typeface.MONOSPACE); channels.setTextIsSelectable(true);
        link = text(body, "", 16); link.setTextIsSelectable(true);
        detail = text(body, "", 13); detail.setTextIsSelectable(true);
        render();
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(23,39,59)); view.setPadding(0,dp(8),0,dp(8)); parent.addView(view); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        boolean active = session.state() == ReadOnlySession.State.CONNECTING || session.state() == ReadOnlySession.State.READING;
        start.setEnabled(!active); stop.setEnabled(active);
        status.setText(session.message() + "\nSDK 识别: " + session.device() + "  |  回调计数: " + session.samples()
                + "\n本地接收距今: " + age(session.age()) + (session.stale() ? "  [无数据 / 已过期]" : "  [近期回调]")
                + "\n源端采样新鲜度：未验证，不能据此授权控制");
        status.setTextColor(session.stale() ? Color.rgb(145,63,0) : Color.rgb(23,39,59));
        int[] raw = session.channels(); StringBuilder out = new StringBuilder();
        if (raw == null) out.append("等待 G12 SDK 真实数据；没有模拟值");
        else {
            if (session.stale()) out.append("[历史读数，已过期]\n");
            for (int i = 0; i < raw.length; i++) {
                out.append(String.format(Locale.ROOT, "CH%02d %6d   ", i + 1, raw[i]));
                if (i % 3 == 2) out.append('\n');
            }
        }
        channels.setText(out.toString());
        link.setText("接收机链路（SDK 报告）: " + receiverState() + "\n原始链路回调距今: " + age(session.signalAge())
                + "\n信号质量原值: " + (session.quality() == null ? "未知" : session.quality()) + "，距今 " + age(session.qualityAge())
                + (session.qualityAge() < 0 || session.qualityAge() >= ReadOnlySession.SIGNAL_STALE_MS ? " [未知 / 已过期]" : "")
                + "\n飞控数传: 未检测（未创建数传管道）\n" + session.warning());
        detail.setText("设备: " + Build.MANUFACTURER + " / " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")  ABI " + Arrays.toString(Build.SUPPORTED_ABIS)
                + "\n系统构建: " + Build.DISPLAY
                + "\n链路原始报告（仅本机显示）: " + (session.signal() == null ? "无" : session.signal())
                + "\n退出前台即停止；超时/断开后必须手动重试。硬件时间戳、双机互斥和受控交还仍待验证。此包不含接管、解锁、校准或刷写入口。");
    }
    private String receiverState() {
        if (session.signal() == null) return "未知（未收到报告）";
        if (session.signalAge() >= ReadOnlySession.SIGNAL_STALE_MS) return "未知（报告已过期）";
        try {
            Object value = new JSONObject(session.signal()).opt("dev_connect");
            if (Boolean.TRUE.equals(value)) return "已连接（未独立验证）";
            if (Boolean.FALSE.equals(value)) return "未连接";
            return "未知（报告未提供布尔 dev_connect）";
        } catch (JSONException e) { return "未知（SDK 报告格式不匹配）"; }
    }
    private static String age(long value) { return value < 0 ? "无" : value + " ms"; }
    @Override protected void onResume() { super.onResume(); main.post(tick); }
    @Override protected void onPause() {
        main.removeCallbacks(tick); session.stop("已离开前台，采集停止；请手动开始"); super.onPause();
    }
}
