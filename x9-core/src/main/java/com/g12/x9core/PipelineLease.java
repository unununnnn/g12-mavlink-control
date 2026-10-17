package com.g12.x9core;

/** Keeps process-wide SDK ownership until both the write and disconnect return. */
public final class PipelineLease {
    private static boolean held,poisoned;
    private boolean closing,writing,disconnected,released;
    private PipelineLease() { }
    public static synchronized PipelineLease acquire(){
        if(poisoned)throw new IllegalStateException("SDK关闭失败，须结束应用进程后检查");
        if(held)throw new IllegalStateException("旧SDK管道/写调用仍未结束，请稍后重连；持续阻塞时须结束应用进程");
        held=true;return new PipelineLease();
    }
    public synchronized boolean beginWrite(){if(closing||writing)return false;writing=true;return true;}
    public synchronized void endWrite(){writing=false;releaseIfFinished();}
    public synchronized void closing(){closing=true;releaseIfFinished();}
    public synchronized void disconnected(boolean success){
        if(!success)synchronized(PipelineLease.class){poisoned=true;}
        disconnected=true;releaseIfFinished();
    }
    private void releaseIfFinished(){if(closing&&disconnected&&!writing&&!released){released=true;synchronized(PipelineLease.class){held=false;}}}
}
