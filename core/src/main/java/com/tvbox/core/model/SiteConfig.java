package com.tvbox.core.model;

import com.tvbox.core.json.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单个站点（内容源）配置。
 *
 * 关键安全约束：
 *  - type 只允许 "static"（内置静态列表）与 "decl"（声明式规则解析）。
 *  - 原版 TVBox 的 type=0(csp_Spider 子类)、type=1(JS)、type=2(远程 jar/dex)、
 *    type=3(csp_XPath 宏) 均不实现，加载时直接拒绝，从机制上杜绝远程代码执行。
 */
public final class SiteConfig {

    public static final String TYPE_STATIC = "static";
    public static final String TYPE_DECL = "decl";

    /** 被禁止的类型：出现即拒绝加载（对照原版生态的远程代码能力） */
    private static final List<String> FORBIDDEN_TYPES =
            java.util.Arrays.asList("0", "1", "2", "3", "js", "jar", "dex", "spider");

    public String key = "";
    public String name = "";
    public String type = TYPE_DECL;
    public String api = "";
    public boolean searchable = true;
    public boolean filterable = false;
    public boolean enabled = true;
    public int order = 0;

    public Map<String, String> headers = new LinkedHashMap<String, String>();
    /** 声明式规则，type=decl 时有效 */
    public Map<String, Object> rules = new LinkedHashMap<String, Object>();
    /** 静态内容，type=static 时有效 */
    public Map<String, Object> staticData = new LinkedHashMap<String, Object>();
    /** 扩展字段，会注入到模板上下文 */
    public Map<String, Object> ext = new LinkedHashMap<String, Object>();

    public static SiteConfig fromJson(Map<String, Object> m) {
        SiteConfig s = new SiteConfig();
        s.key = Json.str(m.get("key"), "");
        s.name = Json.str(m.get("name"), s.key);
        String t = Json.str(m.get("type"), TYPE_DECL).toLowerCase(Locale.ROOT);
        s.type = normalizeType(t);
        s.api = Json.str(m.get("api"), "");
        s.searchable = Json.bool(m.get("searchable"), true);
        s.filterable = Json.bool(m.get("filterable"), false);
        s.enabled = Json.bool(m.get("enabled"), true);
        s.order = Json.intg(m.get("order"), 0);
        Map<String, Object> h = Json.obj(m.get("headers"));
        if (h != null) {
            for (Map.Entry<String, Object> e : h.entrySet()) {
                s.headers.put(e.getKey(), Json.str(e.getValue(), ""));
            }
        }
        Map<String, Object> r = Json.obj(m.get("rules"));
        if (r != null) {
            s.rules = r;
        }
        Map<String, Object> sd = Json.obj(m.get("static"));
        if (sd != null) {
            s.staticData = sd;
        }
        Map<String, Object> ex = Json.obj(m.get("ext"));
        if (ex != null) {
            s.ext = ex;
        }
        return s;
    }

    private static String normalizeType(String t) {
        if (t == null) {
            return TYPE_DECL;
        }
        String v = t.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_TYPES.contains(v)) {
            throw new ConfigRejectedException(
                    "拒绝加载站点配置：type=" + t + " 属于远程代码执行类内容源，本项目不支持");
        }
        if (v.equals("static") || v.equals("local")) {
            return TYPE_STATIC;
        }
        return TYPE_DECL;
    }

    /** 校验：返回问题列表，空表示通过。 */
    public java.util.List<String> validate() {
        java.util.List<String> problems = new java.util.ArrayList<String>();
        if (key.isEmpty()) {
            problems.add("缺少 key");
        }
        if (name.isEmpty()) {
            problems.add("缺少 name");
        }
        if (type.equals(TYPE_DECL)) {
            if (rules.isEmpty()) {
                problems.add("decl 类型缺少 rules");
            }
            if (!api.isEmpty() && !api.startsWith("http://") && !api.startsWith("https://")) {
                problems.add("api 必须是 http/https 地址");
            }
            String badRule = scanRulesForDanger(rules);
            if (badRule != null) {
                problems.add(badRule);
            }
        }
        return problems;
    }

    /**
     * 递归扫描规则里是否夹带了远程代码字段。
     * 这是一道"即使上游配置被污染也能拦住"的兜底检查。
     */
    private static String scanRulesForDanger(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                if (DANGER_KEYS.contains(k)) {
                    return "规则中出现高危字段: " + k + "（可能是远程代码下发，已拒绝）";
                }
                String r = scanRulesForDanger(e.getValue());
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        if (node instanceof List) {
            for (Object o : (List<?>) node) {
                String r = scanRulesForDanger(o);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        if (node instanceof String) {
            String s = ((String) node).toLowerCase(Locale.ROOT);
            for (String d : DANGER_TEXT) {
                if (s.contains(d)) {
                    return "规则文本中出现高危特征: " + d;
                }
            }
        }
        return null;
    }

    private static final java.util.Set<String> DANGER_KEYS = new java.util.HashSet<String>(
            java.util.Arrays.asList("jar", "dex", "spider", "apiurl", "codeurl", "js", "script",
                    "extendedclass", "classname", "dexurl", "jarurl", "eval"));

    private static final java.util.Set<String> DANGER_TEXT = new java.util.HashSet<String>(
            java.util.Arrays.asList(".jar", ".dex", "dexclassloader", "pathclassloader",
                    "loadclass", "runtime.getruntime", "processbuilder"));

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("name", name);
        m.put("type", type);
        m.put("api", api);
        m.put("searchable", searchable);
        m.put("filterable", filterable);
        m.put("enabled", enabled);
        m.put("order", order);
        m.put("headers", headers);
        m.put("rules", rules);
        m.put("static", staticData);
        m.put("ext", ext);
        return m;
    }

    public Map<String, Object> rule(String name) {
        Object o = rules.get(name);
        return o == null ? Collections.<String, Object>emptyMap() : Json.obj(o);
    }

    public static final class ConfigRejectedException extends RuntimeException {
        public ConfigRejectedException(String m) {
            super(m);
        }
    }
}
