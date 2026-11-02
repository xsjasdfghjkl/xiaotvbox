package com.tvbox.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.net.Http;
import com.tvbox.core.net.SecurityGuard;
import com.tvbox.core.parse.ConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置仓库：负责加载、缓存、校验内容源配置。
 *
 * 无论配置来自内置 assets、本地缓存还是用户填的远端地址，
 * 都必须经过 ConfigLoader.parse() 的准入校验；被拒绝的站点会记录原因并在设置页展示，
 * 绝不会静默生效。
 */
public final class ConfigRepository {

    private static final String PREF = "safetvbox";
    private static final String KEY_URL = "config_url";
    private static final String KEY_CACHE = "config_cache";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final SecurityGuard guard;
    private final Http http;
    private volatile ConfigLoader.Config current;

    public ConfigRepository(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.guard = SecurityGuard.strictDefault();
        this.http = new Http(guard).timeout(8000, 15000).maxBytes(2 * 1024 * 1024);
    }

    public SecurityGuard guard() {
        return guard;
    }

    public Http http() {
        return http;
    }

    /** 当前配置；首次调用会尝试读缓存，没有缓存则用内置示例配置。 */
    public synchronized ConfigLoader.Config current() {
        if (current == null) {
            String cached = prefs.getString(KEY_CACHE, "");
            if (!cached.isEmpty()) {
                try {
                    current = ConfigLoader.parse(cached);
                } catch (RuntimeException ignored) {
                    current = null;
                }
            }
            if (current == null) {
                current = loadBuiltIn();
            }
        }
        return current;
    }

    /** 读取内置示例配置（只包含公开、合法的测试源与自填模板）。 */
    public ConfigLoader.Config loadBuiltIn() {
        InputStream in = null;
        try {
            in = ctx.getAssets().open("config/demo-safe.json");
            String text = readAll(in);
            ConfigLoader.Config cfg = ConfigLoader.parse(text);
            current = cfg;
            prefs.edit().putString(KEY_CACHE, text).putString(KEY_URL, "").apply();
            return cfg;
        } catch (IOException e) {
            throw new RuntimeException("读取内置配置失败: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new RuntimeException("内置配置校验失败: " + e.getMessage());
        } finally {
            close(in);
        }
    }

    /** 从远端地址加载配置：地址本身先过安全守卫，内容再过准入校验。 */
    public ConfigLoader.Config loadFromUrl(String url) {
        guard.check(url);
        Http.Response resp = http.get(url);
        if (!resp.ok()) {
            throw new RuntimeException("下载配置失败: HTTP " + resp.code);
        }
        ConfigLoader.Config cfg = ConfigLoader.parse(resp.body());
        current = cfg;
        prefs.edit().putString(KEY_CACHE, resp.body()).putString(KEY_URL, url).apply();
        return cfg;
    }

    /** 直接用一段文本作为配置（便于粘贴调试）。 */
    public ConfigLoader.Config loadFromText(String text) {
        ConfigLoader.Config cfg = ConfigLoader.parse(text);
        current = cfg;
        prefs.edit().putString(KEY_CACHE, text).putString(KEY_URL, "").apply();
        return cfg;
    }

    public String lastUrl() {
        return prefs.getString(KEY_URL, "");
    }

    public List<SiteConfig> sites() {
        ConfigLoader.Config c = current();
        return c == null ? new ArrayList<SiteConfig>() : c.enabledSites();
    }

    public SiteConfig find(String key) {
        for (SiteConfig s : sites()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }

    public List<String> rejected() {
        ConfigLoader.Config c = current();
        return c == null ? new ArrayList<String>() : c.rejected;
    }

    // ------------------------------------------------------------------ 工具

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
