package com.tvbox.core.path;

import com.tvbox.core.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受限的 JSONPath 求值器（零依赖）。
 *
 * 支持语法子集：
 *   $           根节点
 *   .name       取字段（也允许 ['name']）
 *   [0]         数组下标（负数表示倒数）
 *   [*]         全部元素
 *   [0:3]       切片（左闭右开，:3 / 2: 均支持）
 *   ..name      递归下降查找字段
 *
 * 明确不支持（并且刻意不实现）：
 *   - 过滤表达式 $[?(@.x)]：这类语法等价于引入一门表达式语言，
 *     是表达式注入的常见载体，本项目一律不做。
 *   - 脚本/函数调用。
 * 求值结果为 List（可能为多值），取单个值由调用方决定。
 */
public final class JsonPath {

    private final List<Seg> segs;

    private JsonPath(List<Seg> segs) {
        this.segs = segs;
    }

    public static JsonPath compile(String path) {
        if (path == null) {
            throw new IllegalArgumentException("null path");
        }
        String p = path.trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        if (p.charAt(0) != '$') {
            // 允许省略 $，直接以 .name 或 name 开头
            p = "$." + p;
        }
        List<Seg> segs = new ArrayList<Seg>();
        int i = 1;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                if (i + 1 < p.length() && p.charAt(i + 1) == '.') {
                    i += 2;
                    int st = i;
                    while (i < p.length() && isNameChar(p.charAt(i))) {
                        i++;
                    }
                    if (st == i) {
                        throw new IllegalArgumentException("bad path: " + path);
                    }
                    segs.add(new Seg(Seg.DESC, p.substring(st, i)));
                    continue;
                }
                i++;
                int st = i;
                while (i < p.length() && isNameChar(p.charAt(i))) {
                    i++;
                }
                if (st == i) {
                    throw new IllegalArgumentException("bad path: " + path);
                }
                segs.add(new Seg(Seg.KEY, p.substring(st, i)));
                continue;
            }
            if (c == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) {
                    throw new IllegalArgumentException("unclosed [ in " + path);
                }
                String inner = p.substring(i + 1, end).trim();
                segs.add(parseBracket(inner, path));
                i = end + 1;
                continue;
            }
            throw new IllegalArgumentException("bad path at " + i + ": " + path);
        }
        return new JsonPath(segs);
    }

    private static Seg parseBracket(String inner, String path) {
        if (inner.equals("*")) {
            return new Seg(Seg.WILD, null);
        }
        if (inner.length() >= 2 && (inner.charAt(0) == '\'' || inner.charAt(0) == '"')) {
            char q = inner.charAt(0);
            if (inner.charAt(inner.length() - 1) != q) {
                throw new IllegalArgumentException("bad quoted key in " + path);
            }
            return new Seg(Seg.KEY, inner.substring(1, inner.length() - 1));
        }
        if (inner.indexOf(':') >= 0) {
            String[] ab = inner.split(":", -1);
            if (ab.length != 2) {
                throw new IllegalArgumentException("bad slice in " + path);
            }
            int from = ab[0].trim().isEmpty() ? 0 : Integer.parseInt(ab[0].trim());
            int to = ab[1].trim().isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(ab[1].trim());
            return new Seg(Seg.SLICE, new int[]{from, to});
        }
        try {
            return new Seg(Seg.INDEX, Integer.parseInt(inner));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad index in " + path);
        }
    }

    private static boolean isNameChar(char c) {
        return c != '.' && c != '[' && c != ']';
    }

    /** 求值，返回所有命中值。 */
    public List<Object> evalMulti(Object root) {
        List<Object> cur = new ArrayList<Object>();
        cur.add(root);
        for (Seg s : segs) {
            cur = step(cur, s);
            if (cur.isEmpty()) {
                return Collections.emptyList();
            }
        }
        return cur;
    }

    /** 求值并返回首个命中值，未命中返回 null。 */
    public Object eval(Object root) {
        List<Object> r = evalMulti(root);
        return r.isEmpty() ? null : r.get(0);
    }

    public String evalString(Object root, String def) {
        Object v = eval(root);
        return v == null ? def : Json.str(v, def);
    }

    private static List<Object> step(List<Object> in, Seg s) {
        List<Object> out = new ArrayList<Object>();
        switch (s.type) {
            case Seg.KEY:
                for (Object o : in) {
                    Map<String, Object> m = Json.obj(o);
                    if (m != null && m.containsKey(s.name)) {
                        out.add(m.get(s.name));
                    }
                }
                return out;
            case Seg.INDEX:
                for (Object o : in) {
                    List<Object> l = Json.arr(o);
                    if (l == null || l.isEmpty()) {
                        continue;
                    }
                    int idx = s.idx;
                    if (idx < 0) {
                        idx += l.size();
                    }
                    if (idx >= 0 && idx < l.size()) {
                        out.add(l.get(idx));
                    }
                }
                return out;
            case Seg.WILD:
                for (Object o : in) {
                    List<Object> l = Json.arr(o);
                    if (l != null) {
                        out.addAll(l);
                    } else {
                        Map<String, Object> m = Json.obj(o);
                        if (m != null) {
                            out.addAll(m.values());
                        }
                    }
                }
                return out;
            case Seg.SLICE: {
                int[] r = s.range;
                for (Object o : in) {
                    List<Object> l = Json.arr(o);
                    if (l == null) {
                        continue;
                    }
                    int from = r[0] < 0 ? Math.max(0, l.size() + r[0]) : r[0];
                    int to = Math.min(l.size(), r[1] < 0 ? l.size() + r[1] : r[1]);
                    for (int k = from; k < to; k++) {
                        out.add(l.get(k));
                    }
                }
                return out;
            }
            case Seg.DESC: {
                // 递归下降：深度遍历整棵树，收集所有同名字段
                for (Object o : in) {
                    descend(o, s.name, out, 0);
                }
                return out;
            }
            default:
                return out;
        }
    }

    private static void descend(Object node, String key, List<Object> out, int depth) {
        if (depth > Json.MAX_DEPTH) {
            return;
        }
        Map<String, Object> m = Json.obj(node);
        if (m != null) {
            if (m.containsKey(key)) {
                out.add(m.get(key));
            }
            for (Object v : m.values()) {
                descend(v, key, out, depth + 1);
            }
            return;
        }
        List<Object> l = Json.arr(node);
        if (l != null) {
            for (Object v : l) {
                descend(v, key, out, depth + 1);
            }
        }
    }

    private static final class Seg {
        static final int KEY = 0;
        static final int INDEX = 1;
        static final int WILD = 2;
        static final int SLICE = 3;
        static final int DESC = 4;

        final int type;
        final String name;
        final int idx;
        final int[] range;

        Seg(int type, Object payload) {
            this.type = type;
            this.name = (type == KEY || type == DESC) ? (String) payload : null;
            this.idx = (type == INDEX) ? ((Integer) payload) : 0;
            this.range = (type == SLICE) ? (int[]) payload : null;
        }
    }

    /** 便捷：把 root 下 path 指向的对象抽成 Map，供后续字段提取使用。 */
    public static Map<String, Object> asObject(Object root, String path) {
        Object v = JsonPath.compile(path).eval(root);
        return Json.obj(v);
    }

    public static List<Object> asList(Object root, String path) {
        Object v = JsonPath.compile(path).eval(root);
        return Json.arr(v) != null ? Json.arr(v) : Collections.emptyList();
    }

    /** 把若干 path 结果拼成一个 Map（用于从单条记录里抽取字段）。 */
    public static Map<String, Object> project(Object record, Map<String, String> fieldPaths) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> e : fieldPaths.entrySet()) {
            String p = e.getValue();
            if (p == null || p.isEmpty()) {
                continue;
            }
            try {
                Object v = JsonPath.compile(p).eval(record);
                if (v != null) {
                    out.put(e.getKey(), v);
                }
            } catch (RuntimeException ignored) {
                // 单字段失败不应导致整条记录丢失
            }
        }
        return out;
    }
}
