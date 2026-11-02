package com.tvbox.core.html;

import com.tvbox.core.html.HtmlParser.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 受限 CSS 选择器引擎（零依赖）。
 *
 * 支持：
 *   tag、#id、.class、[attr]、[attr=value]、*、组合（div.item a.href）
 *   后代空格、直接子 >、并集逗号、:eq(n) 结果索引、:nth-child(n)
 *
 * 不支持（刻意）：伪类函数如 :not(...)/:has(...) 的任意嵌套，避免复杂度爆炸与 ReDoS 风险。
 */
public final class CssSelector {

    private static final int DESC = 0;
    private static final int CHILD = 1;

    private final List<Group> groups;

    private CssSelector(List<Group> groups) {
        this.groups = groups;
    }

    public static List<Node> select(Node root, String css) {
        if (root == null || css == null || css.trim().isEmpty()) {
            return new ArrayList<Node>();
        }
        return compile(css).eval(root);
    }

    public static CssSelector compile(String css) {
        List<Group> groups = new ArrayList<Group>();
        for (String part : splitTop(css, ',')) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            groups.add(parseGroup(p));
        }
        return new CssSelector(groups);
    }

    public List<Node> eval(Node root) {
        List<Node> out = new ArrayList<Node>();
        List<Node> all = new ArrayList<Node>();
        // 根自身也参与匹配：这样对某行节点再执行 "a" 选择器时能取到它自己
        if (!"#root".equals(root.name)) {
            all.add(root);
        }
        flatten(root, all, 0);
        for (Group g : groups) {
            List<Node> hits = new ArrayList<Node>();
            for (Node n : all) {
                if (matchChain(n, g)) {
                    hits.add(n);
                }
            }
            if (g.eq >= 0) {
                if (g.eq < hits.size()) {
                    out.add(hits.get(g.eq));
                }
            } else if (g.eqLast) {
                if (!hits.isEmpty()) {
                    out.add(hits.get(hits.size() - 1));
                }
            } else {
                out.addAll(hits);
            }
        }
        return out;
    }

    private static void flatten(Node n, List<Node> out, int depth) {
        if (depth > 64) {
            return;
        }
        for (Node c : n.children) {
            if ("#text".equals(c.name)) {
                continue;
            }
            out.add(c);
            flatten(c, out, depth + 1);
        }
    }

    private static boolean matchChain(Node n, Group g) {
        List<Simple> chain = g.chain;
        int last = chain.size() - 1;
        if (!match(n, chain.get(last))) {
            return false;
        }
        Node cur = n;
        for (int k = last - 1; k >= 0; k--) {
            int comb = g.combinators.get(k);
            if (comb == CHILD) {
                cur = cur.parent;
                if (cur == null || !match(cur, chain.get(k))) {
                    return false;
                }
            } else {
                boolean found = false;
                Node up = cur.parent;
                while (up != null && !"#root".equals(up.name)) {
                    if (match(up, chain.get(k))) {
                        found = true;
                        cur = up;
                        break;
                    }
                    up = up.parent;
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean match(Node n, Simple s) {
        if (s.tag != null && !s.tag.equals("*") && !s.tag.equals(n.name)) {
            return false;
        }
        if (s.id != null && !s.id.equals(n.attr("id"))) {
            return false;
        }
        for (String c : s.classes) {
            String cls = n.attr("class", "");
            if (!(" " + cls + " ").contains(" " + c + " ")) {
                return false;
            }
        }
        for (String[] a : s.attrs) {
            String v = n.attr(a[0]);
            if (v == null) {
                return false;
            }
            if (a[1] != null && !a[1].equals(v)) {
                return false;
            }
        }
        if (s.nthChild > 0) {
            List<Node> sibs = new ArrayList<Node>();
            if (n.parent != null) {
                for (Node c : n.parent.children) {
                    if (!"#text".equals(c.name)) {
                        sibs.add(c);
                    }
                }
            }
            int idx = sibs.indexOf(n) + 1;
            if (idx != s.nthChild) {
                return false;
            }
        }
        return true;
    }

    private static Group parseGroup(String p) {
        Group g = new Group();
        // 先剥离末尾的 :eq(n)
        int eqIdx = p.lastIndexOf(":eq(");
        if (eqIdx >= 0 && p.endsWith(")")) {
            String num = p.substring(eqIdx + 4, p.length() - 1).trim();
            if (num.equals("last")) {
                g.eqLast = true;
            } else {
                try {
                    g.eq = Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    g.eq = -1;
                }
            }
            p = p.substring(0, eqIdx);
        }
        // 按空格 / > 切分
        List<String> tokens = tokenize(p);
        for (int k = 0; k < tokens.size(); k++) {
            String t = tokens.get(k);
            if (t.equals(">")) {
                if (!g.chain.isEmpty()) {
                    g.combinators.set(g.chain.size() - 1, CHILD);
                }
                continue;
            }
            g.chain.add(parseSimple(t));
            if (g.combinators.size() < g.chain.size()) {
                g.combinators.add(DESC);
            }
        }
        while (g.combinators.size() < g.chain.size()) {
            g.combinators.add(DESC);
        }
        return g;
    }

    private static List<String> tokenize(String p) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '>') {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                out.add(">");
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
                continue;
            }
            if (c == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) {
                    end = p.length() - 1;
                }
                sb.append(p, i, end + 1);
                i = end + 1;
                continue;
            }
            sb.append(c);
            i++;
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    private static Simple parseSimple(String t) {
        Simple s = new Simple();
        String rest = t;
        // 属性
        while (rest.indexOf('[') >= 0) {
            int a = rest.indexOf('[');
            int b = rest.indexOf(']', a);
            if (b < 0) {
                break;
            }
            String inner = rest.substring(a + 1, b);
            int eq = inner.indexOf('=');
            if (eq < 0) {
                s.attrs.add(new String[]{inner.trim().toLowerCase(Locale.ROOT), null});
            } else {
                String k = inner.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = inner.substring(eq + 1).trim();
                if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                    v = v.substring(1, v.length() - 1);
                }
                s.attrs.add(new String[]{k, v});
            }
            rest = rest.substring(0, a) + rest.substring(b + 1);
        }
        int nth = rest.indexOf(":nth-child(");
        if (nth >= 0) {
            int close = rest.indexOf(')', nth);
            if (close > nth) {
                try {
                    s.nthChild = Integer.parseInt(rest.substring(nth + 11, close).trim());
                } catch (NumberFormatException ignored) {
                    s.nthChild = -1;
                }
                rest = rest.substring(0, nth) + rest.substring(close + 1);
            }
        }
        // id / class / tag
        StringBuilder tag = new StringBuilder();
        for (int k = 0; k < rest.length(); k++) {
            char c = rest.charAt(k);
            if (c == '#') {
                int e = endOfToken(rest, k + 1);
                s.id = rest.substring(k + 1, e);
                k = e - 1;
            } else if (c == '.') {
                int e = endOfToken(rest, k + 1);
                s.classes.add(rest.substring(k + 1, e));
                k = e - 1;
            } else {
                tag.append(c);
            }
        }
        String tg = tag.toString().trim().toLowerCase(Locale.ROOT);
        s.tag = tg.isEmpty() ? null : tg;
        if (s.tag == null && s.id == null && s.classes.isEmpty() && s.attrs.isEmpty()) {
            s.tag = "*";
        }
        return s;
    }

    private static int endOfToken(String s, int from) {
        int k = from;
        while (k < s.length()) {
            char c = s.charAt(k);
            if (c == '.' || c == '#' || c == '[' || c == ':') {
                break;
            }
            k++;
        }
        return k;
    }

    private static List<String> splitTop(String s, char delim) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == delim && depth == 0) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    private static final class Group {
        List<Simple> chain = new ArrayList<Simple>();
        List<Integer> combinators = new ArrayList<Integer>();
        int eq = -1;
        boolean eqLast = false;
    }

    private static final class Simple {
        String tag;
        String id;
        List<String> classes = new ArrayList<String>();
        List<String[]> attrs = new ArrayList<String[]>();
        int nthChild = -1;
    }
}
