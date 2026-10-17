package com.g12.x9;
import android.app.Application;
import com.g12.bench.ReadOnlySession;
import com.g12.bench.RetainedReadBackend;
import com.g12.bench.SkydroidBackend;
public final class X9Application extends Application {
    private ReadOnlySession.Backend backend;
    public ReadOnlySession.Backend backend(){if(backend==null)backend=new RetainedReadBackend(new SkydroidBackend(this));return backend;}
}
