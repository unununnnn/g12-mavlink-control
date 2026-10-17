package com.g12.x9;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.g12.bench.ReadOnlySession;
import com.g12.x9core.ChannelSession;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One explicit connect button. No saved identity, setup wizard or calibration. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> guard;
    private final ChannelSession engine=new ChannelSession(SystemClock::elapsedRealtime);
    private ReadOnlySession input;
    private boolean pending;
    private long forwarded=-1;
    private String notice="未连接";
    private TextView state,detail;
    private Button connect;
    private final LinearLayout[] cells=new LinearLayout[18];
    private final TextView[] labels=new TextView[18];
    private final ProgressBar[] bars=new ProgressBar[18];
    private final Runnable uiTick=new Runnable(){@Override public void run(){
        input.tick();
        if(pending){
            if(input.state()==ReadOnlySession.State.STOPPED){pending=false;notice=input.message();}
            else if(input.state()==ReadOnlySession.State.READING&&input.channels()!=null&&!input.stale())openPipe();
        }
        if(engine.active()){
            if(input.state()==ReadOnlySession.State.READING&&input.channels()!=null&&input.samples()!=forwarded){forwarded=input.samples();engine.input(input.channels(),input.receiptMillis(),forwarded);}
            else if(input.state()==ReadOnlySession.State.STOPPED)engine.fault(input.message());
        }
        if(engine.state()==ChannelSession.State.FAULT&&input.state()!=ReadOnlySession.State.STOPPED)input.stop("通道发送已停止；请手动重连");
        render();main.postDelayed(this,50);
    }};
    @Override public void onCreate(Bundle b){super.onCreate(b);input=new ReadOnlySession(((X9Application)getApplication()).backend(),SystemClock::elapsedRealtime);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,20,24,28);body.setBackgroundColor(Color.rgb(244,247,251));scroll.addView(body);setContentView(scroll);
        text(body,"G12 · 通道直传",27);text(body,"0.5.0  /  全部通道原值 · 连接后自动发送",16);
        connect=button(body,"连接",this::toggle);state=text(body,"未连接",19);detail=text(body,"",14);
        for(int row=0;row<9;row++){LinearLayout line=new LinearLayout(this);body.addView(line);
            for(int col=0;col<2;col++){int i=row*2+col;LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(0,0,16,4);line.addView(cell,new LinearLayout.LayoutParams(0,-2,1));cells[i]=cell;
                labels[i]=text(cell,"CH"+(i+1)+"  —",16);bars[i]=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bars[i].setMax(1400);cell.addView(bars[i]);}}
        button(body,"诊断 / 释放SDK端口",()->new android.app.AlertDialog.Builder(this).setTitle("连接诊断").setMessage(diagnostics()).setPositiveButton("关闭",null)
            .setNeutralButton("释放SDK端口",(d,w)->{disconnect("已释放SDK端口",true);render();}).show());
        text(body,"辅助开关也会传给飞控。断开即停发，最后覆盖值按飞控现有超时处理。",13);render();
    }
    private TextView text(LinearLayout body,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.rgb(26,44,66));v.setPadding(0,8,0,8);body.addView(v);return v;}
    private Button button(LinearLayout body,String label,Runnable action){Button b=new Button(this);b.setText(label);body.addView(b);b.setOnClickListener(v->{try{action.run();}catch(RuntimeException|LinkageError bad){disconnect("操作失败："+bad.getMessage(),true);}render();});return b;}
    private void toggle(){if(engine.active()||pending){disconnect("已断开，停止刷新通道",false);return;}
        engine.stop("准备连接");pending=true;forwarded=-1;notice="";input.start();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void openPipe(){pending=false;try{SdkLink link=new SdkLink(engine);engine.connect(link,new SecureRandom().nextInt(256));link.open();forwarded=-1;}
        catch(RuntimeException|LinkageError bad){engine.fault(bad.getMessage()==null?"连接失败":bad.getMessage());}}
    private void disconnect(String reason,boolean release){pending=false;engine.stop(reason);forwarded=-1;if(release)input.stop(reason);else input.pause(reason);notice=reason;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void render(){if(state==null)return;
        connect.setText(engine.active()||pending?"断开":"连接");state.setText(pending?"正在读取摇杆…":engine.active()||engine.state()==ChannelSession.State.FAULT?engine.status():notice);
        int[] raw=engine.channels();int count=raw==null?4:raw.length;
        for(int i=0;i<18;i++){cells[i].setVisibility(i<count?View.VISIBLE:View.GONE);labels[i].setText("CH"+(i+1)+"  "+(raw!=null&&i<raw.length?raw[i]:"—"));bars[i].setProgress(raw!=null&&i<raw.length?Math.max(0,Math.min(1400,raw[i]-800)):0);}
        detail.setText(engine.active()?"目标 "+engine.targetSystem()+" / "+engine.targetComponent()+" · MAVLink"+engine.wireVersion()+"\n发送通道 "+(raw==null?0:raw.length)+" · 已提交 "+engine.offered()+" · SDK返回 "+engine.returned()
            +(raw!=null&&raw.length>16?"\n4.7.1仅采用前16通道；17～18已发出但未确认采用":""):"无需校准或绑定；按SDK通道顺序直传");
    }
    private String diagnostics(){return "SDK："+input.message()+"\n发送状态："+engine.status()+"\n源ID "+engine.sourceSystem()+" / 191 · 最高10Hz"
        +"\n心跳年龄 "+engine.heartbeatAge()+"ms · RC反馈年龄 "+engine.rcAge()+"ms · 前16通道匹配观察 "+engine.matched()
        +"\n覆盖超时 "+engine.overrideTimeout()+"秒\n无总时长限制，无解锁状态门槛。SDK返回不是UART完成或飞控逐帧ACK。"
        +"\n尚未真机验收；源端采样新鲜度、跨设备互斥和主SBUS交还未验证。";}
    @Override protected void onResume(){super.onResume();main.post(uiTick);guard=watchdog.scheduleWithFixedDelay(()->{try{engine.tick();}catch(RuntimeException bad){engine.fault("发送监视异常："+bad.getClass().getSimpleName());}},0,25,TimeUnit.MILLISECONDS);}
    @Override protected void onPause(){main.removeCallbacks(uiTick);if(guard!=null)guard.cancel(false);disconnect("进入后台，已停发；需手动连接",true);super.onPause();}
    @Override protected void onDestroy(){watchdog.shutdownNow();super.onDestroy();}
}
