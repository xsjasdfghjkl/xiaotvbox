package com.tvbox.core.parse;

import com.tvbox.core.expr.Expr;
import com.tvbox.core.html.CssSelector;
import com.tvbox.core.html.HtmlParser;
import com.tvbox.core.json.Json;
import com.tvbox.core.path.JsonPath;
import com.tvbox.core.util.Text;

import java.util.List;
import java.util.Map;

/**
 * 字段提取表达式。一条规则里每个字段如何取值，由本类描述。
 *
 * 支持的写法（按前缀区分）：
 *   $.a.b / data.list[0].title   JSONPath（受限子集，见 JsonPath）
 *   css:div.item a@href          CSS 选择器 + 取属性（@text / @html / @outerHtml / @任意属性）
 *   re:(.*?)@1                   正则提取第 1 组
 *   普通字符串                    字面量（其中的 ${var} 会做变量替换）
 *
 * 这里刻意不提供任何"执行"能力：没有脚本、没有函数调用、没有反射。
 */
public final class FieldSpec {

    public static final int CONST = 0;
    public static final int JSON = 1;
    public static final int CSS = 2;
    public static final int REGEX = 3;

    public final int kind;
    public final String expr;
    public final String attr;
    public final int group;

    private JsonPath compiled;
    private CssSelector compiledCss;

    private FieldSpec(int kind, String expr, String attr, int group) {
        this.kind = kind;
        this.expr = expr;
        this.attr = attr;
        this.group = group;
    }

    public static FieldSpec of(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return null;
        }
        String s = spec.trim();
        if (s.startsWith("css:")) {
            String rest = s.substring(4);
            int at = rest.lastIndexOf('@');
            String sel = at >= 0 ? rest.substring(0, at) : rest;
            String attr = at >= 0 ? rest.substring(at + 1) : "text";
            FieldSpec f = new FieldSpec(CSS, sel.trim(), attr.trim(), 1);
            f.compiledCss = CssSelector.compile(sel.trim());
            return f;
        }
        if (s.startsWith("re:")) {
            String rest = s.substring(3);
            int at = rest.lastIndexOf('@');
            String pattern = at >= 0 ? rest.substring(0, at) : rest;
            int group = 1;
            if (at >= 0) {
                try {
                    group = Integer.parseInt(rest.substring(at + 1).trim());
                } catch (NumberFormatException ignored) {
                    group = 1;
                }
            }
            return new FieldSpec(REGEX, pattern, null, group);
        }
        if (s.startsWith("$")) {
            FieldSpec f = new FieldSpec(JSON, s, null, 1);
            f.compiled = JsonPath.compile(s);
            return f;
        }
        return new FieldSpec(CONST, s, null, 1);
    }

    /**
     * 从一个上下文节点里取值。
     *
     * @param ctx  上下文：JSON 模式下为 Object；HTML 模式下为 HtmlParser.Node；正则模式下为 String
     * @param vars 模板变量
     */
    public String extract(Object ctx, Map<String, Object> vars) {
        if (ctx == null) {
            return "";
        }
        switch (kind) {
            case JSON: {
                if (compiled == null) {
                    return "";
                }
                Object v = compiled.eval(ctx);
                return v == null ? "" : Json.str(v, "");
            }
            case CSS: {
                if (!(ctx instanceof HtmlParser.Node)) {
                    return "";
                }
                HtmlParser.Node root = (HtmlParser.Node) ctx;
                List<HtmlParser.Node> hits = compiledCss.eval(root);
                if (hits.isEmpty()) {
                    // 根节点自身也可能匹配（如直接给出 li 选择器）
                    return "";
                }
                HtmlParser.Node n = hits.get(0);
                if (attr == null || attr.isEmpty() || attr.equals("text")) {
                    return n.text();
                }
                if (attr.equals("html")) {
                    return n.html();
                }
                if (attr.equals("outerHtml")) {
                    return n.outerHtml();
                }
                String v = n.attr(attr);
                return v == null ? "" : v;
            }
            case REGEX: {
                String src = (ctx instanceof String) ? (String) ctx : String.valueOf(ctx);
                String v = Text.findFirst(src, expr, group);
                return v == null ? "" : v;
            }
            default:
                return Expr.render(expr, vars);
        }
    }

    /** 某些字段需要取"所有命中并用 # 连接"，如多集地址。 */
    public String extractAll(Object ctx, Map<String, Object> vars, String sep) {
        if (kind == CSS && ctx instanceof HtmlParser.Node) {
            List<HtmlParser.Node> hits = compiledCss.eval((HtmlParser.Node) ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                HtmlParser.Node n = hits.get(i);
                if (attr == null || attr.isEmpty() || attr.equals("text")) {
                    sb.append(n.text());
                } else if (attr.equals("html")) {
                    sb.append(n.html());
                } else {
                    String v = n.attr(attr);
                    sb.append(v == null ? "" : v);
                }
            }
            return sb.toString();
        }
        if (kind == JSON) {
            List<Object> all = compiled.evalMulti(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(Json.str(all.get(i), ""));
            }
            return sb.toString();
        }
        return extract(ctx, vars);
    }
}
