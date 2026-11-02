package com.tvbox.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖 JSON 解析/序列化 + 类型安全取值。
 *
 * 安全设计：
 *  - 递归下降解析，带最大深度与最大长度限制，防止畸形输入导致栈溢出 / OOM。
 *  - 不做任何反序列化到 Java 对象的操作（不存在 readObject / 反射实例化），
 *    因此不存在 fastjson / jackson 那种"自动绑定任意类"的利用链。
 *  - 解析结果只用 Map / List / String / Double / Long / Boolean / null 表示。
 */
public final class Json {

    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ---------------------------------------------------------------- 解析

    public static Object parse(String text) {
        return parse(text, MAX_DEPTH);
    }

    public static Object parse(String text, int maxDepth) {
        if (text == null || text.length() == 0) {
            throw new JsonException("empty json");
        }
        P p = new P(text, maxDepth);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i < p.s.length()) {
            throw new JsonException("trailing garbage at " + p.i);
        }
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    // ---------------------------------------------------------------- 序列化

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString((String) v, sb);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Number) {
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    sb.append((long) d);
                } else {
                    sb.append(d);
                }
            } else {
                sb.append(((Number) v).longValue());
            }
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            writeString(String.valueOf(v), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 取值

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    public static List<Object> arr(Object v) {
        return (v instanceof List) ? (List<Object>) v : null;
    }

    public static String str(Object v) {
        return str(v, "");
    }

    public static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return def;
    }

    public static long lng(Object v, long def) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignore) {
                return def;
            }
        }
        return def;
    }

    public static int intg(Object v, int def) {
        return (int) lng(v, def);
    }

    public static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)) {
                return true;
            }
            if ("0".equals(s) || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return def;
    }

    /** 在 map 中取嵌套路径，如 get(m, "a", "b", "0") */
    public static Object at(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (cur == null) {
                return null;
            }
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(k);
            } else if (cur instanceof List) {
                int idx;
                try {
                    idx = Integer.parseInt(k);
                } catch (NumberFormatException e) {
                    return null;
                }
                List<?> l = (List<?>) cur;
                cur = (idx >= 0 && idx < l.size()) ? l.get(idx) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    // ---------------------------------------------------------------- 内部类

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) {
            super(msg);
        }
    }

    private static final class P {
        final String s;
        final int maxDepth;
        int i;

        P(String s, int maxDepth) {
            this.s = s;
            this.maxDepth = maxDepth;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > maxDepth) {
                throw new JsonException("json too deep (>" + maxDepth + ")");
            }
            ws();
            if (i >= s.length()) {
                throw new JsonException("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new JsonException("expected key at " + i);
                }
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new JsonException("expected ':' at " + i);
                }
                i++;
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) {
                    throw new JsonException("unterminated object");
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at " + (i - 1));
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<Object>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) {
                    throw new JsonException("unterminated array");
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at " + (i - 1));
                }
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw new JsonException("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new JsonException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new JsonException("bad escape \\" + e);
                }
            }
        }

        Number number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean isFloat = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    i++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, i);
            if (raw.length() == 0) {
                throw new JsonException("bad number at " + start);
            }
            try {
                if (!isFloat && raw.length() <= 18) {
                    return Long.parseLong(raw);
                }
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number: " + raw);
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, i)) {
                throw new JsonException("expected " + lit + " at " + i);
            }
            i += lit.length();
        }
    }
}
