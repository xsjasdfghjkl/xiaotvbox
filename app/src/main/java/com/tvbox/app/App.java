package com.tvbox.app;

import android.app.Application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全局入口。这里不放任何统计、上报、远程开关之类的东西——它只有线程池和配置仓库。 */
public class App extends Application {

    private static App sInstance;

    /**
     * 统一的 IO 线程池，所有网络/解析都在这里跑，主线程不做网络。
     * 用 cached 而非固定大小：搜索会并发请求多个内容源，固定池在源较多时可能互相等死。
     */
    public static final ExecutorService IO = Executors.newCachedThreadPool();

    private ConfigRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // 崩溃记录器要第一个装上：后面任何一步炸了都能留下线索
        CrashLogger.install(this);
        CrashLogger.log("=== App 启动 ===");
        CrashLogger.logEnv();

        try {
            repository = new ConfigRepository(this);
            CrashLogger.log("配置仓库初始化成功");
        } catch (Throwable t) {
            // 不重新抛出：配置挂了也要让界面起来并显示错误，而不是闪退
            CrashLogger.log("配置仓库初始化失败", t);
        }
    }

    public static App get() {
        return sInstance;
    }

    public static ConfigRepository repo() {
        return sInstance.repository;
    }
}
