package com.tvbox.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录器：把未捕获异常写到本应用专属目录。
 *
 * 存在的理由：没有 adb、没有 Android Studio 时（比如只在模拟器上装 APK），
 * "点开闪一下就没"是没有任何线索的。装上它之后，闪退会自动留下一个 txt，
 * 用模拟器自带的文件管理器就能打开。
 *
 * 文件位置（getExternalFilesDir，不需要任何存储权限）：
 *   /sdcard/Android/data/com.tvbox.app/files/crash.txt
 */
public final class CrashLogger {

    private static final String TAG = "SafeTVBox-Crash";
    private static final String FILE = "crash.txt";

    private static File dir;
    private static Thread.UncaughtExceptionHandler previous;
    private static boolean installed;

    private CrashLogger() {
    }

    public static void install(Context ctx) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            File d = ctx.getExternalFilesDir(null);
            if (d == null) {
                d = ctx.getFilesDir();
            }
            dir = d;
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            // 每次启动先清空，避免看到上一次的旧日志造成误判
            File f = new File(dir, FILE);
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log("!!! 崩溃 !!! 线程=" + t.getName(), e);
                if (previous != null) {
                    try {
                        previous.uncaughtException(t, e);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        });
    }

    public static void log(String msg) {
        log(msg, null);
    }

    public static void log(String msg, Throwable t) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(stamp()).append("] ").append(msg);
            if (t != null) {
                sb.append('\n').append(stack(t));
            }
            sb.append('\n');
            // 同时打进 logcat，方便有 adb 的人直接看
            Log.e(TAG, sb.toString());
            append(sb.toString());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 记录一行环境信息，排查时很有用 */
    public static void logEnv() {
        log("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + ")，设备 " + Build.MODEL + " / " + Build.PRODUCT);
    }

    private static void append(String text) {
        if (dir == null) {
            return;
        }
        FileOutputStream fos = null;
        OutputStreamWriter w = null;
        try {
            fos = new FileOutputStream(new File(dir, FILE), true);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(text);
            w.flush();
        } catch (Throwable ignored) {
            // ignore
        } finally {
            close(w);
            close(fos);
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
