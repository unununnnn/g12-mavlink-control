package com.g12.x9;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Color;
import android.view.WindowManager;
import android.widget.*;
import android.text.InputType;
import com.g12.bench.ReadOnlySession;
import com.g12.x9core.Calibration;
import com.g12.x9core.DirectSession;
import com.g12.x9core.Profile;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Explicit first-use binding; thereafter one connect button and a physical takeover switch. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> guard;
    private final DirectSession engine=new DirectSession(SystemClock::elapsedRealtime);
    private ReadOnlySession input;
    private ProfileStore store;
    private Profile saved;
    private Calibration draft;
    private boolean setup,pendingOpen,binding;
    private long forwarded=-1;
    private TextView state,detail,instruction,rawView;
    private Button connect,capture,bind;
    private EditText baud;
    private final TextView[] axisText=new TextView[4];
    private final ProgressBar[] bars=new ProgressBar[4];
    private final int[][] captures=new int[14][];
    private int step;
    private String notice="",setupError="";
    private static final String[] PROMPTS={"横滚：左打到底","横滚：右打到底","横滚：回到中位","俯仰：向后拉到底","俯仰：向前推到底","俯仰：回到中位","油门：最低位置","油门：最高位置","油门：行程中位","偏航：向左到底","偏航：向右到底","偏航：回到中位","接管开关：放在关闭位置","接管开关：放在开启位置"};
    private final Runnable uiTick=new Runnable(){@Override public void run(){
        input.tick();
        if(pendingOpen){
            if(input.state()==ReadOnlySession.State.STOPPED){pendingOpen=false;notice=input.message();}
            else if(input.state()==ReadOnlySession.State.READING&&input.channels()!=null&&!input.stale())openPipe();
        }
        if(engine.active()){
            if(input.state()==ReadOnlySession.State.READING&&input.channels()!=null&&input.samples()!=forwarded){forwarded=input.samples();engine.input(input.channels(),input.receiptMillis(),forwarded);}
            else if(input.state()==ReadOnlySession.State.STOPPED)engine.fault(input.message());
        }
        if(engine.state()==DirectSession.State.FAULT&&input.state()!=ReadOnlySession.State.STOPPED)input.stop("数传故障，采集已停止；请手动连接");
        render();main.postDelayed(this,50);
    }};
    @Override public void onCreate(Bundle b){super.onCreate(b);input=new ReadOnlySession(((X9Application)getApplication()).backend(),SystemClock::elapsedRealtime);store=new ProfileStore(this);
        try{saved=store.load();}catch(Exception bad){notice="档案读取失败，请重新设置："+bad.getMessage();}home();}
    private LinearLayout screen(String title){ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,20,24,28);body.setBackgroundColor(Color.rgb(244,247,251));scroll.addView(body);setContentView(scroll);text(body,title,27);return body;}
    private TextView text(LinearLayout body,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(26,44,66));t.setPadding(0,10,0,10);body.addView(t);return t;}
    private Button button(LinearLayout body,String label,Runnable action){Button b=new Button(this);b.setText(label);body.addView(b);b.setOnClickListener(v->{try{action.run();}catch(Exception|LinkageError e){notice=e.getMessage();setupError=notice;}render();});return b;}
    private void home(){setup=false;LinearLayout body=screen("G12 · X9直连");text(body,"0.4.0  /  连接后待命 · 物理开关请求传输",15);
        connect=button(body,saved==null?"首次设置":"连接",this::toggle);state=text(body,"",19);
        for(int i=0;i<4;i++){axisText[i]=text(body,new String[]{"横滚","俯仰","油门","偏航"}[i],16);bars[i]=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bars[i].setMax(2000);body.addView(bars[i]);}
        detail=text(body,"",14);detail.setTextIsSelectable(true);
        button(body,saved==null?"设置设备档案":"设备档案 / 重新设置",this::setupScreen);
        button(body,"诊断 / 释放SDK端口",()->new android.app.AlertDialog.Builder(this).setTitle("连接诊断").setMessage(diagnostics())
            .setPositiveButton("关闭",null).setNeutralButton("释放SDK端口",(d,w)->{disconnect("已释放SDK端口",true);render();}).show());
        text(body,"持续传输 · 无解锁状态限制\n尚未完成真机验收；停发不代表主控已接回。",13);render();}
    private void toggle(){if(engine.active()||pendingOpen){disconnect("用户断开；未自动发送释放帧",false);return;}if(saved==null){setupScreen();return;}
        engine.stop("准备手动连接");binding=false;draft=saved.calibration;notice="";pendingOpen=true;forwarded=-1;input.start();}
    private void disconnect(String why,boolean release){pendingOpen=false;engine.stop(why);forwarded=-1;if(release)input.stop(why);else input.pause(why);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);notice=why;}
    private void openPipe(){pendingOpen=false;try{SdkLink link=new SdkLink(engine);engine.connect(draft,binding?null:saved,link,new SecureRandom().nextInt(256));link.open();forwarded=-1;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        catch(RuntimeException|LinkageError e){engine.fault(e.getMessage()==null?"连接失败":e.getMessage());notice=engine.status();}}
    private void setupScreen(){disconnect("进入设置，已停止传输",false);setup=true;binding=false;step=0;draft=null;setupError="";Arrays.fill(captures,null);
        LinearLayout body=screen("首次设置 · 保存一次");text(body,"先确认其他SDK应用已关闭。只移动提示的一个摇杆或开关；本页校准阶段不发送飞控控制。",15);
        baud=new EditText(this);baud.setSingleLine(true);baud.setInputType(InputType.TYPE_CLASS_NUMBER);baud.setHint("GR00透传口与X9串口的实际波特率");if(saved!=null)baud.setText(Integer.toString(saved.calibration.baud));body.addView(baud);
        text(body,"仅记录实际波特率，不自动修改GR00或飞控参数。物理接口以底板手册为准。",13);
        button(body,"开始 / 继续读取摇杆",()->{if(engine.state()==DirectSession.State.FAULT)engine.stop("重新采集");input.start();});instruction=text(body,"",19);rawView=text(body,"",14);
        capture=button(body,"记录当前位置",this::capture);
        button(body,"重新记录全部位置",()->{if(engine.active()||pendingOpen)disconnect("重新校准",false);step=0;draft=null;binding=false;baud.setEnabled(true);Arrays.fill(captures,null);setupError="";input.start();});
        bind=button(body,"读取X9并绑定",this::bind);button(body,"返回",()->{disconnect("设置已退出",false);home();});
        text(body,"系统："+Build.MODEL+" / Android "+Build.VERSION.RELEASE+" / "+Build.DISPLAY+"\n需厂商20250110或以后的数传系统。未提供有效身份或参数时停止，不猜测配置。",13);input.start();render();}
    private int[] snapshot(){if(input.state()!=ReadOnlySession.State.READING||input.stale()||input.channels()==null)throw new IllegalStateException("先取得近期真实摇杆数据");return input.channels();}
    private void capture(){if(step>=14)return;captures[step]=snapshot();
        if(step%3==2&&step<12)Calibration.capture(captures[step-2],captures[step-1],captures[step]);
        if(step==13){Calibration.Axis[] axes=new Calibration.Axis[4];for(int i=0;i<4;i++)axes[i]=Calibration.capture(captures[i*3],captures[i*3+1],captures[i*3+2]);
            int ch=Calibration.changed(captures[12],captures[13]);int rate=Integer.parseInt(baud.getText().toString().trim());draft=new Calibration(captures[0].length,axes,ch,captures[12][ch],captures[13][ch],rate);}
        step++;if(step==14)baud.setEnabled(false);setupError="";
    }
    private void bind(){
        if(draft==null)throw new IllegalStateException("先完成摇杆与开关设置");
        if(engine.active()){
            Profile p=engine.candidate();if(p==null||engine.state()!=DirectSession.State.STANDBY||engine.heartbeatAge()>=DirectSession.TELEMETRY_MS||engine.rcAge()<0||engine.rcAge()>=DirectSession.TELEMETRY_MS)
                throw new IllegalStateException("等待身份、配置和近期RC遥测全部核对完成");
            store.save(p);saved=p;disconnect("档案已保存；现在可一键连接",false);home();return;
        }
        snapshot();engine.stop("准备绑定");binding=true;pendingOpen=true;forwarded=-1;setupError="";input.start();
    }
    private void render(){
        if(setup){if(instruction==null)return;instruction.setText(step<14?"第 "+(step+1)+" / 14 步："+PROMPTS[step]+"，然后点击记录":summary());
            rawView.setText(input.message()+"\n原始通道："+Arrays.toString(input.channels())+"\n"+(engine.active()||engine.state()==DirectSession.State.FAULT?engine.status():"")+"\n"+setupError);
            capture.setEnabled(step<14&&!engine.active());bind.setEnabled(draft!=null&&!pendingOpen&&(!engine.active()||engine.candidate()!=null));bind.setText(engine.candidate()!=null&&engine.active()?"确认此X9并保存绑定":"读取X9并绑定");return;}
        if(state==null)return;connect.setText(engine.active()||pendingOpen?"断开":saved==null?"首次设置":"连接");
        state.setText(pendingOpen?"正在读取摇杆…":engine.active()||engine.state()==DirectSession.State.FAULT?engine.status():notice.isEmpty()?"未连接":notice);
        int[] values=engine.logical();String[] labels={"横滚","俯仰","油门","偏航"};for(int i=0;i<4;i++){axisText[i].setText(labels[i]+"  "+(values==null?"—":values[i]));bars[i].setProgress(values==null?0:i==2?values[i]*2:values[i]+1000);}
        detail.setText(saved==null?"尚未保存设备档案":"已绑定 X9 "+saved.system+" / "+saved.component+" · 接管开关 CH"+(saved.calibration.switchChannel+1));
    }
    private String diagnostics(){return saved==null?"尚未保存设备档案":
            "X9 "+saved.system+" / "+saved.component+" · UID "+saved.uid+"\n"+saved.calibration.baud+" baud · "+saved.rateHz()+"Hz · 开关CH"+(saved.calibration.switchChannel+1)
            +"\n心跳年龄 "+engine.heartbeatAge()+"ms · RC反馈 "+engine.rcAge()+"ms\n飞控状态："+(!engine.active()||engine.heartbeatAge()<0||engine.heartbeatAge()>=DirectSession.TELEMETRY_MS?"未知":engine.armed()?"已解锁":"未解锁")
            +"\n提交 "+engine.offered()+" · SDK返回 "+engine.returned()+" · RC匹配观察 "+engine.matched()+"\n覆盖超时 "+saved.value("RC_OVERRIDE_TIME")+"秒；反馈不代表独占权确认"
            +"\nSDK回调不证明硬件采样新鲜度。本版未实现空端跨设备仲裁。";
    }
    private String summary(){if(draft==null)return "校准未完成";StringBuilder b=new StringBuilder("摇杆已记录：");for(int i=0;i<4;i++)b.append("\n").append(Profile.ROLES[i]).append(" = CH").append(draft.axis(i).channel+1).append(" [").append(draft.axis(i).min).append(", ").append(draft.axis(i).center).append(", ").append(draft.axis(i).max).append("]");
        b.append("\n接管开关CH").append(draft.switchChannel+1);Profile p=engine.candidate();if(p!=null)b.append("\nX9 ").append(p.system).append('/').append(p.component).append("\nUID ").append(p.uid).append("\nArduCopter 4.7.1；仅本地绑定，不改写飞控参数");return b.toString();}
    @Override protected void onResume(){super.onResume();main.post(uiTick);guard=watchdog.scheduleAtFixedRate(()->{try{engine.tick();}catch(RuntimeException e){engine.fault("发送监视异常："+e.getClass().getSimpleName());}},0,25,TimeUnit.MILLISECONDS);}
    @Override protected void onPause(){main.removeCallbacks(uiTick);if(guard!=null)guard.cancel(false);disconnect("已进入后台；需手动连接",true);super.onPause();}
    @Override protected void onDestroy(){watchdog.shutdownNow();super.onDestroy();}
}
