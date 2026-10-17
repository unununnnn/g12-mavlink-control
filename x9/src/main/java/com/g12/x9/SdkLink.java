package com.g12.x9;
import android.os.SystemClock;
import com.g12.x9core.DirectSession;
import com.g12.x9core.PipelineLease;
import com.skydroid.rcsdk.PipelineManager;
import com.skydroid.rcsdk.comm.CommListener;
import com.skydroid.rcsdk.common.error.SkyException;
import com.skydroid.rcsdk.common.pipeline.Pipeline;
import com.skydroid.rcsdk.common.pipeline.PipelineType;
import java.io.File;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real RCSDK pipe. One local write, no application queue; SDK return is not UART completion. */
public final class SdkLink implements DirectSession.Link {
    private final DirectSession session;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
    private volatile Pipeline pipeline;
    private volatile boolean active,ready;
    private volatile PipelineLease lease;
    public SdkLink(DirectSession s){session=s;}
    public void open(){
        if(active||closing.get())throw new IllegalStateException("管道状态无效");
        if(!new File("/system/bin/data_stream").isFile())throw new IllegalStateException("缺少厂商data_stream；本应用不安装辅助程序或刷写系统");
        lease=PipelineLease.acquire();active=true;
        try{
            Pipeline p=PipelineManager.INSTANCE.createG12G20Pipeline();if(p==null||p.getPipelineType()!=PipelineType.UDP)throw new IllegalStateException("SDK管道类型不匹配");pipeline=p;
            p.setOnCommListener(new CommListener(){
                @Override public void onConnectSuccess(){if(active)ready=true;}
                @Override public void onConnectFail(SkyException e){session.faultFrom(SdkLink.this,"SDK管道连接失败："+(e==null?"未知":e.getErrorCode()));}
                @Override public void onDisconnect(){session.faultFrom(SdkLink.this,"数传管道断开，需手动连接");}
                @Override public void onReadData(byte[] bytes){if(bytes!=null)session.receiveFrom(SdkLink.this,bytes.clone());}
            });PipelineManager.INSTANCE.connectPipeline(p);
        }catch(RuntimeException|LinkageError e){close();throw e;}
    }
    @Override public boolean connected(){return active&&ready&&pipeline!=null;}
    @Override public boolean offer(byte[] frame,long deadline,DirectSession.Done done){
        PipelineLease current=lease;if(!connected()||current==null||!current.beginWrite())return false;byte[] data=frame.clone();Pipeline p=pipeline;
        try{writer.execute(()->{boolean ok=false;try{if(active&&p==pipeline&&connected()&&p.isConnected()&&SystemClock.elapsedRealtime()<deadline){p.writeData(data);ok=active&&SystemClock.elapsedRealtime()<deadline;}}
            catch(RuntimeException|LinkageError e){ok=false;}finally{current.endWrite();done.returned(ok);}});return true;}
        catch(RejectedExecutionException e){current.endWrite();return false;}
    }
    @Override public void close(){active=ready=false;if(!closing.compareAndSet(false,true))return;PipelineLease current=lease;if(current!=null)current.closing();writer.shutdownNow();Pipeline previous=pipeline;pipeline=null;
        if(previous!=null){Thread thread=new Thread(()->{boolean ok=false;try{PipelineManager.INSTANCE.disconnectPipeline(previous);ok=true;}catch(RuntimeException|LinkageError e){ok=false;}finally{if(current!=null)current.disconnected(ok);}},"x9-close");thread.setDaemon(true);thread.start();}
        else if(current!=null)current.disconnected(true);
    }
}
