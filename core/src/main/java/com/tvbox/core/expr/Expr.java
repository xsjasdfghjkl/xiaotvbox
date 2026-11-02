package com.tvbox.core.expr;

import com.tvbox.core.json.Json;
import com.tvbox.core.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板渲染器：把 "http://x/${id}.json" 这类模板按上下文填充。
 *
 * 安全设计（关键）：
 *  - 这不是表达式语言，不做 eval。占位符内部只允许变量名（字母/数字/下划线/点）
 *    与一组白名单"管道函数"，其余字符一律视为字面量，绝不解析执行。
 *  - 白名单函数不含任何文件、网络、反射、类加载能力。
 *  - 原版 TVBox 生态里 type=1/2 的 JS / jar 执行能力在本项目中被彻底移除，
 *    本类就是它的安全替代品。
 */
public final class Expr {

    private Expr() {
    }

    /** 变量白名单函数个数上限，防止超长管道拖慢解析 */
    private static final int MAX_PIPES = 8;

    public static String render(String tpl, Map<String, Object> ctx) {
        if (tpl == null) {
            return "";
        }
        if (tpl.indexOf("${") < 0) {
            return tpl;
        }
        StringBuilder sb = new StringBuilder(tpl.length() + 32);
        int i = 0;
        while (i < tpl.length()) {
            int st = tpl.indexOf("${", i);
            if (st < 0) {
                sb.append(tpl, i, tpl.length());
                break;
            }
            sb.append(tpl, i, st);
            int end = tpl.indexOf('}', st + 2);
            if (end < 0) {
                sb.append(tpl, st, tpl.length());
                break;
            }
            String inner = tpl.substring(st + 2, end);
            sb.append(resolve(inner, ctx));
            i = end + 1;
        }
        return sb.toString();
    }

    private static String resolve(String inner, Map<String, Object> ctx) {
        String[] parts = inner.split("\\|", -1);
        if (parts.length > MAX_PIPES) {
            return "";
        }
        String name = parts[0].trim();
        if (!isPlainName(name)) {
            // 不是合法变量名：原样回显，绝不解析
            return "${" + inner + "}";
        }
        String value = lookup(name, ctx);
        for (int k = 1; k < parts.length; k++) {
            value = applyFn(value, parts[k].trim());
        }
        return value == null ? "" : value;
    }

    private static boolean isPlainName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 支持 a.b.c 形式的逐级取值 */
    private static String lookup(String name, Map<String, Object> ctx) {
        if (ctx == null) {
            return "";
        }
        Object direct = ctx.get(name);
        if (direct == null && name.indexOf('.') >= 0) {
            String[] keys = name.split("\\.");
            Object cur = ctx;
            for (String k : keys) {
                Map<String, Object> m = Json.obj(cur);
                if (m == null) {
                    cur = null;
                    break;
                }
                cur = m.get(k);
            }
            direct = cur;
        }
        return direct == null ? "" : Json.str(direct, "");
    }

    private static String applyFn(String value, String fnSpec) {
        if (fnSpec.isEmpty()) {
            return value;
        }
        String fn = fnSpec;
        String arg = null;
        String arg2 = null;
        int colon = fnSpec.indexOf(':');
        if (colon >= 0) {
            fn = fnSpec.substring(0, colon);
            String rest = fnSpec.substring(colon + 1);
            int c2 = rest.indexOf(':');
            if (c2 >= 0) {
                arg = rest.substring(0, c2);
                arg2 = rest.substring(c2 + 1);
            } else {
                arg = rest;
            }
        }
        fn = fn.toLowerCase(java.util.Locale.ROOT);
        switch (fn) {
            case "urlencode":
            case "url":
                return Text.urlEncode(value);
            case "urldecode":
                return Text.urlDecode(value);
            case "base64":
                return Text.base64Encode(value);
            case "base64d":
            case "base64decode":
                return Text.base64Decode(value);
            case "md5":
                return Text.md5(value);
            case "upper":
                return value.toUpperCase(java.util.Locale.ROOT);
            case "lower":
                return value.toLowerCase(java.util.Locale.ROOT);
            case "trim":
                return value.trim();
            case "len":
                return String.valueOf(value.length());
            case "int":
                try {
                    return String.valueOf(Long.parseLong(value.trim()));
                } catch (NumberFormatException e) {
                    return "0";
                }
            case "concat":
                return value + (arg == null ? "" : arg);
            case "replace":
                if (arg == null || arg2 == null) {
                    return value;
                }
                return value.replace(arg, arg2);
            case "sub":
            case "substring": {
                int a = arg == null ? 0 : Integer.parseInt(arg);
                if (arg2 == null) {
                    return a >= 0 && a <= value.length() ? value.substring(a) : value;
                }
                int b = Integer.parseInt(arg2);
                b = Math.min(b, value.length());
                return (a >= 0 && a <= b) ? value.substring(a, b) : value;
            }
            case "pad":
                return value;
            default:
                // 未知函数：不做任何处理，也不报错抛出，保证配置容错
                return value;
        }
    }

    /** 同上，输入已是 Map<String, String>（如站点级 headers）。 */
    public static Map<String, String> renderStringMap(Map<String, String> raw, Map<String, Object> ctx) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            out.put(e.getKey(), render(e.getValue(), ctx));
        }
        return out;
    }

    /** 渲染一个 Map 里的所有字符串值（用于 headers / form 参数）。 */
    public static Map<String, String> renderMap(Map<String, Object> raw, Map<String, Object> ctx) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            out.put(e.getKey(), render(Json.str(e.getValue(), ""), ctx));
        }
        return out;
    }

    /** 把 list 里每一项渲染后拼接（用于 URL 参数拼接）。 */
    public static List<String> renderList(List<Object> raw, Map<String, Object> ctx) {
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            out.add(render(Json.str(o, ""), ctx));
        }
        return out;
    }
}
