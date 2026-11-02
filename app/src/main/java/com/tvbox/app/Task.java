package com.tvbox.app;

import android.os.Handler;
import android.os.Looper;

/** 极简异步封装：IO 线程执行，主线程回调。 */
public final class Task {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Task() {
    }

    public interface Job<T> {
        T run() throws Exception;
    }

    public interface Callback<T> {
        void done(T result, Throwable error);
    }

    public static <T> void run(Job<T> job, Callback<T> cb) {
        App.IO.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Throwable error = null;
                try {
                    result = job.run();
                } catch (Throwable t) {
                    error = t;
                }
                final T r = result;
                final Throwable e = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(r, e);
                    }
                });
            }
        });
    }
}
