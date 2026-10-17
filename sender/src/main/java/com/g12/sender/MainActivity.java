package com.g12.sender;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.graphics.Color;
import android.widget.*;
import android.view.View;
import com.g12.bench.ReadOnlySession;
import com.g12.sendercore.Mapping;
import com.g12.sendercore.SendEngine;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> guard;
    private ReadOnlySession input;
    private SendEngine engine;
    private SdkPipelineLink link;
    private Mapping mapping;
    private long forwarded = -1;
    private TextView live, note;
    private EditText src, srcComp, dst, dstComp, baud, rate;
    private final EditText[][] fields = new EditText[4][5];
    private final CheckBox[] reverse = new CheckBox[4];
    private CheckBox isolated, firmware;
    private Spinner message;
    private String decision = "请先读取真实原始通道，再填写映射。";
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            input.tick();
            if (input.state() == ReadOnlySession.State.READING && input.samples() != forwarded && input.channels() != null) {
                forwarded = input.samples(); engine.sample(input.channels(),input.receiptMillis(),forwarded,true);
            } else if (input.state() != ReadOnlySession.State.READING && engine.state() != SendEngine.State.STOPPED) {
                engine.stop("遥控器输入断开");
            }
            if (link != null) link.checkConnectTimeout();
            render(); main.postDelayed(this,100);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        input = new ReadOnlySession(((SenderApplication)getApplication()).backend(),SystemClock::elapsedRealtime);
        engine = new SendEngine(SystemClock::elapsedRealtime);
        ScrollView scroll = new ScrollView(this); LinearLayout body = column();
        body.setPadding(18,12,18,20); body.setBackgroundColor(Color.rgb(245,248,252)); scroll.addView(body);setContentView(scroll);
        text(body,"G12 串口台架发送 · v0.3.1",24);
        text(body,"真实SDK → GR00 UART → 电脑收包/逐帧回显\n不是SBUS。不是双链路接管版。飞控必须断开。",17);
        text(body,"默认停止；每次最多10秒、每个新回调最多一帧、最多一帧在途。回显仅证明字节返回，不证明飞控接受。源端新鲜度未获厂商证明。",15);
        button(body,"1. 开始真实摇杆读取",() -> { stopLink("重新开始读取");forwarded=-1;input.start(); });
        button(body,"停止采集与发送（保留SDK连接）",() -> { stopLink("用户停止：不自动发送释放帧");input.pause("采集已停止；SDK连接保留，可再次开始读取");forwarded=-1; });
        button(body,"断开SDK / 释放端口",() -> { stopLink("用户释放SDK端口");input.stop("SDK端口已请求释放");forwarded=-1; });
        text(body,"日常停止/再读使用上方停止采集按钮。切换助手或其他App前释放端口；退到后台也会释放。再次读取不会自动开始发送。",15);
        live=text(body,"",16);
        text(body,"2. 必填配置（改动立即停止，须重新确认）",20);
        message=new Spinner(this); message.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"请选择消息类型","MANUAL_CONTROL (69)","RC_CHANNELS_OVERRIDE (70)"}));body.addView(message);
        message.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){invalidateMapping();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){invalidateMapping();}
        });
        src=field(body,"发送者 sysid 1..255");srcComp=field(body,"发送者 compid 1..255");
        dst=field(body,"目标 sysid 1..255");dstComp=field(body,"目标 compid 1..255");
        text(body,"MANUAL_CONTROL只有target system字段，不携带target component；该模式的compid仅记录，不能定位某组件。RC覆盖模式同时携带两者。",14);
        baud=field(body,"GR00 UART0与电脑已核对波特率（本App不修改）");rate=field(body,"发送频率1..10Hz；低波特率需降频");
        String[] roles={"ROLL 横滚","PITCH 俯仰","THROTTLE 油门","YAW 偏航"};
        for(int i=0;i<4;i++){
            text(body,roles[i],18);
            String[] names={"输入CH（SDK数组从1开始）","原始最小值","原始中位值","原始最大值","目标RC通道1..8（MANUAL填1，仅记录不使用）"};
            for(int j=0;j<5;j++)fields[i][j]=field(body,names[j]);
            reverse[i]=new CheckBox(this);reverse[i].setText("反向此轴");body.addView(reverse[i]);reverse[i].setOnCheckedChangeListener((b,c)->invalidateMapping());
        }
        text(body,"归一化：横滚/俯仰/偏航 -1000..1000，油门0..1000。RC覆盖输出1000..2000µs；只写所选4个通道，其余ignore。不得据此假定X9映射正确。",14);
        isolated=new CheckBox(this);isolated.setText("我已确认GR00只接电脑串口，飞控断开，收包脚本已启动并会逐帧回显");body.addView(isolated);
        firmware=new CheckBox(this);firmware.setText("我已核对G12厂商Android系统构建为20250110或以后（仅Android13版本号不够）");body.addView(firmware);
        isolated.setOnCheckedChangeListener((b,c)->{if(!c)stopLink("台架确认已撤销");});
        firmware.setOnCheckedChangeListener((b,c)->{if(!c)stopLink("系统确认已撤销");});
        button(body,"3. 确认配置并预览真实映射",this::confirmMapping);
        button(body,"4. 打开官方G12数据管道（尚不发送控制帧）",this::openLink);
        button(body,"5. 明确开始发送（最多10秒）",() -> {
            if(mapping==null || link==null)throw new IllegalStateException("先确认映射并打开管道");
            engine.start(mapping,link,isolated.isChecked(),firmware.isChecked(),new SecureRandom().nextInt(256));
            decision="已手动启动：SDK返回数、电脑回显数分开显示；无飞控接受结论。";
        });
        button(body,"立即停止发送（无释放帧）",() -> stopLink("用户停止；已入SDK/链路的在途帧无法撤回"));
        button(body,"仅RC模式：发一次所选通道释放帧并停止",() -> {
            engine.releaseAndStop();decision="释放只用于电脑收包检查，不证明主SBUS恢复；故障时不会自动释放。";
        });
        note=text(body,"",15);
        text(body,"系统 "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+" / "+Build.DISPLAY+"\nABI "+Arrays.toString(Build.SUPPORTED_ABIS)
                +"\nSDK默认本地14551 → 127.0.0.1:14552；应用不修改UART波特率/飞控参数。切后台、锁屏、进程重启均需重新手动启动。",13);
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(LinearLayout parent,String label,int size){TextView v=new TextView(this);v.setText(label);v.setTextSize(size);v.setPadding(0,8,0,8);parent.addView(v);return v;}
    private EditText field(LinearLayout parent,String label){
        EditText e=new EditText(this);e.setHint(label);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);parent.addView(e);
        e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){invalidateMapping();}public void afterTextChanged(Editable e){}});return e;
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);parent.addView(b);b.setOnClickListener(v->{try{action.run();}catch(RuntimeException|LinkageError e){decision=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}render();});}
    private int number(EditText field){try{return Integer.parseInt(field.getText().toString().trim());}catch(NumberFormatException e){throw new IllegalArgumentException("请填写："+field.getHint());}}
    private void invalidateMapping(){mapping=null;stopLink("配置变动，已停止；须重新确认");decision="配置尚未确认";}
    private void stopLink(String reason){if(engine!=null)engine.stop(reason);if(link!=null){link.close();link=null;}}
    private void confirmMapping(){
        if(message.getSelectedItemPosition()==0)throw new IllegalArgumentException("请选择消息类型");
        Mapping.Axis[] axes=new Mapping.Axis[4];
        for(int i=0;i<4;i++)axes[i]=new Mapping.Axis(number(fields[i][0]),number(fields[i][1]),number(fields[i][2]),number(fields[i][3]),reverse[i].isChecked(),number(fields[i][4]));
        Mapping next=new Mapping(message.getSelectedItemPosition()==1?Mapping.Message.MANUAL_CONTROL:Mapping.Message.RC_CHANNELS_OVERRIDE,
                number(src),number(srcComp),number(dst),number(dstComp),number(baud),number(rate),axes);
        if(input.state()!=ReadOnlySession.State.READING || input.stale())throw new IllegalStateException("先取得近期真实原始通道");
        int[] preview=next.values(input.channels());mapping=next;decision="配置已确认："+next.message+"，源="+next.sourceSystem+"/"+next.sourceComponent
                +"，目标="+next.targetSystem+"/"+next.targetComponent+"，UART="+next.baud+"，"+next.rateHz+"Hz。ROLL/PITCH/THROTTLE/YAW="+Arrays.toString(preview)+"；请检查方向。";
    }
    private void openLink(){
        if(mapping==null || input.state()!=ReadOnlySession.State.READING || !isolated.isChecked() || !firmware.isChecked())throw new IllegalStateException("须确认映射、电脑隔离、厂商系统构建，并保持输入连接");
        stopLink("手动打开新管道");link=new SdkPipelineLink(engine);link.open();
    }
    private void render(){
        if(live==null)return;
        String values=Arrays.toString(input.channels());
        String preview="未确认";
        if(mapping!=null && input.channels()!=null){try{preview=Arrays.toString(mapping.values(input.channels()));}catch(IllegalArgumentException e){preview=e.getMessage();}}
        live.setText(input.message()+"\n原始CH01起: "+values+"\n本地回调年龄 "+input.age()+"ms（不证明硬件源端新鲜度）"
                +"\n映射 R/P/T/Y: "+preview+"\n管道: "+(link==null?"未打开":link.status())
                +"\n发送状态: "+engine.state()+" / "+engine.status()+"\n提交帧="+engine.offered()+" SDK调用返回="+engine.sdkReturns()+" 电脑回显="+engine.echoed()
                +"\n飞控接受：未验证；双链路排他：未部署");
        // Keep live feedback next to the send/stop controls after scrolling through the configuration form.
        if(note!=null)note.setText(decision+"\n"+engine.state()+"："+engine.status()+"\nSDK返回="+engine.sdkReturns()+" / 电脑回显="+engine.echoed()
                +"\n当前映射 R/P/T/Y="+preview+"；回调年龄="+input.age()+"ms");
    }
    @Override protected void onResume(){super.onResume();main.post(uiTick);guard=watchdog.scheduleAtFixedRate(()->{try{engine.tick();}catch(RuntimeException e){engine.stop("发送监视异常");}},0,25,TimeUnit.MILLISECONDS);}
    @Override protected void onPause(){main.removeCallbacks(uiTick);if(guard!=null)guard.cancel(false);stopLink("后台/锁屏：停止，不自动恢复");input.stop("已离开前台");forwarded=-1;super.onPause();}
    @Override protected void onDestroy(){watchdog.shutdownNow();super.onDestroy();}
}
