package com.tvbox.core.html;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 零依赖 HTML 解析：把 HTML 文本解析成一棵轻量 DOM 树。
 *
 * 安全说明：
 *  - 这是纯数据解析器，不执行 script、不加载外部资源（img/src 只作为字符串属性保留）。
 *  - 解析深度/节点数有上限，防止恶意超大页面导致 OOM。
 *  - 不依赖 Jsoup 等第三方库，减少供应链风险。
 */
public final class HtmlParser {

    public static final int MAX_NODES = 20000;

    private final String src;
    private int i;
    private int nodes;

    private HtmlParser(String src) {
        this.src = src;
    }

    public static Node parse(String html) {
        return new HtmlParser(html == null ? "" : html).parseDocument();
    }

    private Node parseDocument() {
        Node root = new Node("#root");
        parseChildren(root, null);
        return root;
    }

    private void parseChildren(Node parent, String stopTag) {
        while (i < src.length()) {
            int lt = src.indexOf('<', i);
            if (lt < 0) {
                addText(parent, src.substring(i));
                i = src.length();
                return;
            }
            if (lt > i) {
                addText(parent, src.substring(i, lt));
            }
            i = lt;
            if (startsWith("<!--")) {
                int end = src.indexOf("-->", i + 4);
                i = (end < 0) ? src.length() : end + 3;
                continue;
            }
            if (startsWith("<!")) {
                int end = src.indexOf('>', i + 2);
                i = (end < 0) ? src.length() : end + 1;
                continue;
            }
            if (startsWith("</")) {
                int end = src.indexOf('>', i);
                if (end < 0) {
                    i = src.length();
                    return;
                }
                String name = src.substring(i + 2, end).trim().toLowerCase(Locale.ROOT);
                i = end + 1;
                if (stopTag != null && name.equals(stopTag)) {
                    return;
                }
                continue; // 孤立闭标签，忽略
            }
            // 开标签
            int end = findTagEnd(i);
            if (end < 0) {
                addText(parent, src.substring(i));
                i = src.length();
                return;
            }
            String raw = src.substring(i + 1, end);
            i = end + 1;
            boolean selfClose = raw.endsWith("/");
            if (selfClose) {
                raw = raw.substring(0, raw.length() - 1);
            }
            TagHead head = parseTagHead(raw);
            if (head == null || head.name.isEmpty()) {
                continue;
            }
            if (nodes++ > MAX_NODES) {
                return;
            }
            Node n = new Node(head.name);
            n.attrs = head.attrs;
            n.parent = parent;
            parent.children.add(n);
            if (selfClose || VOID.contains(head.name)) {
                continue;
            }
            if ("script".equals(head.name) || "style".equals(head.name)) {
                int close = findCloseTag(head.name);
                if (close >= 0) {
                    n.text = src.substring(i, close);
                    i = src.indexOf('>', close) + 1;
                }
                continue;
            }
            parseChildren(n, head.name);
        }
    }

    private int findCloseTag(String name) {
        String lower = src.toLowerCase(Locale.ROOT);
        String needle = "</" + name;
        int p = lower.indexOf(needle, i);
        return p < 0 ? -1 : p;
    }

    private void addText(Node parent, String raw) {
        if (raw.isEmpty()) {
            return;
        }
        String t = decodeEntities(raw);
        if (t.trim().isEmpty() && parent.children.isEmpty()) {
            return;
        }
        Node tnode = new Node("#text");
        tnode.parent = parent;
        tnode.text = t;
        parent.children.add(tnode);
    }

    private int findTagEnd(int from) {
        boolean q = false;
        char quote = 0;
        for (int k = from + 1; k < src.length(); k++) {
            char c = src.charAt(k);
            if (q) {
                if (c == quote) {
                    q = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                q = true;
                quote = c;
            } else if (c == '>') {
                return k;
            }
        }
        return -1;
    }

    private boolean startsWith(String s) {
        return src.startsWith(s, i);
    }

    private static TagHead parseTagHead(String raw) {
        raw = raw.trim();
        int sp = 0;
        while (sp < raw.length() && !Character.isWhitespace(raw.charAt(sp))) {
            sp++;
        }
        String name = raw.substring(0, sp).toLowerCase(Locale.ROOT);
        if (name.startsWith("!")) {
            return null;
        }
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        int k = sp;
        while (k < raw.length()) {
            while (k < raw.length() && Character.isWhitespace(raw.charAt(k))) {
                k++;
            }
            int st = k;
            while (k < raw.length() && !Character.isWhitespace(raw.charAt(k)) && raw.charAt(k) != '=') {
                k++;
            }
            if (k == st) {
                k++;
                continue;
            }
            String an = raw.substring(st, k).toLowerCase(Locale.ROOT);
            while (k < raw.length() && Character.isWhitespace(raw.charAt(k))) {
                k++;
            }
            String av = "";
            if (k < raw.length() && raw.charAt(k) == '=') {
                k++;
                while (k < raw.length() && Character.isWhitespace(raw.charAt(k))) {
                    k++;
                }
                if (k < raw.length() && (raw.charAt(k) == '"' || raw.charAt(k) == '\'')) {
                    char q = raw.charAt(k++);
                    int vs = k;
                    while (k < raw.length() && raw.charAt(k) != q) {
                        k++;
                    }
                    av = raw.substring(vs, Math.min(k, raw.length()));
                    if (k < raw.length()) {
                        k++;
                    }
                } else {
                    int vs = k;
                    while (k < raw.length() && !Character.isWhitespace(raw.charAt(k))) {
                        k++;
                    }
                    av = raw.substring(vs, k);
                }
            }
            attrs.put(an, decodeEntities(av));
        }
        TagHead h = new TagHead();
        h.name = name;
        h.attrs = attrs;
        return h;
    }

    public static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int semi = s.indexOf(';', k);
            if (semi < 0 || semi - k > 12) {
                sb.append(c);
                continue;
            }
            String ent = s.substring(k + 1, semi);
            String rep = ENTITIES.get(ent);
            if (rep != null) {
                sb.append(rep);
                k = semi;
                continue;
            }
            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                try {
                    sb.append((char) Integer.parseInt(ent.substring(2), 16));
                    k = semi;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fallthrough
                }
            } else if (ent.startsWith("#")) {
                try {
                    sb.append((char) Integer.parseInt(ent.substring(1)));
                    k = semi;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fallthrough
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static final class TagHead {
        String name;
        Map<String, String> attrs;
    }

    private static final List<String> VOID = java.util.Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    private static final Map<String, String> ENTITIES;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("amp", "&");
        m.put("lt", "<");
        m.put("gt", ">");
        m.put("quot", "\"");
        m.put("apos", "'");
        m.put("nbsp", "\u00a0");
        m.put("#39", "'");
        ENTITIES = Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ Node

    public static final class Node {
        public final String name;
        public Map<String, String> attrs = Collections.emptyMap();
        public final List<Node> children = new ArrayList<Node>();
        public Node parent;
        public String text;

        Node(String name) {
            this.name = name;
        }

        public String attr(String key) {
            return attrs.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        }

        public String attr(String key, String def) {
            String v = attr(key);
            return v == null ? def : v;
        }

        /** 自身及后代的纯文本 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            collectText(sb);
            return sb.toString().trim();
        }

        private void collectText(StringBuilder sb) {
            if ("#text".equals(name)) {
                sb.append(text == null ? "" : text);
                return;
            }
            for (Node c : children) {
                c.collectText(sb);
            }
        }

        public String ownText() {
            if (text != null) {
                return text.trim();
            }
            StringBuilder sb = new StringBuilder();
            for (Node c : children) {
                if ("#text".equals(c.name)) {
                    sb.append(c.text == null ? "" : c.text);
                }
            }
            return sb.toString().trim();
        }

        public List<Node> select(String css) {
            return CssSelector.select(this, css);
        }

        public Node selectFirst(String css) {
            List<Node> r = CssSelector.select(this, css);
            return r.isEmpty() ? null : r.get(0);
        }

        public List<Node> children() {
            List<Node> out = new ArrayList<Node>();
            for (Node c : children) {
                if (!"#text".equals(c.name)) {
                    out.add(c);
                }
            }
            return out;
        }

        public String outerHtml() {
            if ("#text".equals(name)) {
                return text == null ? "" : text;
            }
            StringBuilder sb = new StringBuilder();
            sb.append('<').append(name);
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                sb.append(' ').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            }
            sb.append('>');
            for (Node c : children) {
                sb.append(c.outerHtml());
            }
            if (!VOID.contains(name)) {
                sb.append("</").append(name).append('>');
            }
            return sb.toString();
        }

        public String html() {
            StringBuilder sb = new StringBuilder();
            for (Node c : children) {
                sb.append(c.outerHtml());
            }
            return sb.toString();
        }
    }
}
