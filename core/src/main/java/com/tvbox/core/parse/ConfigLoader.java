package com.tvbox.core.parse;

import com.tvbox.core.json.Json;
import com.tvbox.core.model.SiteConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置加载与准入校验。
 *
 * 这是整个安全模型的第一道闸门：任何一份配置（无论来自本地文件、assets 还是远端 URL）
 * 都必须先经过 parse()，里面包含的站点会被逐个 validate()。
 * 凡涉及远程代码执行的类型都会被直接拒绝，而不是"先加载再说"。
 */
public final class ConfigLoader {

    public static final int MAX_SITES = 200;
    public static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;

    private ConfigLoader() {
    }

    public static final class Config {
        public final List<SiteConfig> sites = new ArrayList<SiteConfig>();
        public final List<String> warnings = new ArrayList<String>();
        public final Map<String, Object> raw = new LinkedHashMap<String, Object>();
        /** 被拒绝的站点及原因，UI 应展示给用户而不是静默丢弃 */
        public final List<String> rejected = new ArrayList<String>();

        public List<SiteConfig> enabledSites() {
            List<SiteConfig> out = new ArrayList<SiteConfig>();
            for (SiteConfig s : sites) {
                if (s.enabled) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    public static Config parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("配置内容为空");
        }
        if (text.length() > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("配置过大（上限 " + MAX_CONFIG_BYTES + " 字节）");
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置不是合法 JSON: " + e.getMessage());
        }
        Config cfg = new Config();
        List<Object> siteList = null;
        Map<String, Object> rootObj = Json.obj(root);
        if (rootObj != null) {
            cfg.raw.putAll(rootObj);
            siteList = Json.arr(rootObj.get("sites"));
            if (siteList == null) {
                siteList = Json.arr(rootObj.get("list"));
            }
            // 顶层若出现高危字段，直接告警
            for (String k : rootObj.keySet()) {
                if (k.equalsIgnoreCase("spider") || k.equalsIgnoreCase("jar")
                        || k.equalsIgnoreCase("dex") || k.equalsIgnoreCase("code")) {
                    cfg.rejected.add("配置顶层存在高危字段 '" + k + "'，已忽略（本项目不加载任何可执行代码）");
                }
            }
        } else if (Json.arr(root) != null) {
            siteList = Json.arr(root);
        }
        if (siteList == null) {
            throw new IllegalArgumentException("配置中找不到 sites 数组");
        }
        int count = 0;
        for (Object o : siteList) {
            if (++count > MAX_SITES) {
                cfg.rejected.add("站点数量超过上限 " + MAX_SITES + "，多余部分被忽略");
                break;
            }
            Map<String, Object> m = Json.obj(o);
            if (m == null) {
                continue;
            }
            try {
                SiteConfig s = SiteConfig.fromJson(m);
                List<String> problems = s.validate();
                if (!problems.isEmpty()) {
                    cfg.rejected.add(s.name + " (" + s.key + "): " + join(problems));
                    continue;
                }
                cfg.sites.add(s);
            } catch (SiteConfig.ConfigRejectedException e) {
                cfg.rejected.add(e.getMessage());
            } catch (RuntimeException e) {
                cfg.rejected.add("站点解析失败: " + e.getMessage());
            }
        }
        return cfg;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
