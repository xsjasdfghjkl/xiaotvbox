package com.tvbox.core.parse;

import com.tvbox.core.expr.Expr;
import com.tvbox.core.html.HtmlParser;
import com.tvbox.core.json.Json;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.net.Http;
import com.tvbox.core.net.SecurityGuard;
import com.tvbox.core.path.JsonPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 声明式 Spider：用配置（数据）描述站点，而不是用代码。
 *
 * 对照原版 TVBox / CatVod 的 Spider 接口，这里实现了同名的五个方法：
 *   homeContent / categoryContent / detailContent / searchContent / playerContent
 * 但实现方式完全不同：
 *   - 原版靠"站点下发 JS 或 jar，客户端加载执行"；
 *   - 本项目靠"客户端解析 JSON/HTML 规则"，配置只是数据，永远不会变成可执行代码。
 *
 * 这一层没有任何 ClassLoader、没有脚本引擎、没有反射调用。
 */
public final class DeclSpider {

    private final SiteConfig site;
    private final Http http;

    public DeclSpider(SiteConfig site, Http http) {
        this.site = site;
        this.http = http;
    }

    public SiteConfig site() {
        return site;
    }

    // ------------------------------------------------------------ 变量上下文

    private Map<String, Object> vars(Map<String, Object> extra) {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("api", site.api);
        v.put("key", site.key);
        v.put("name", site.name);
        for (Map.Entry<String, Object> e : site.ext.entrySet()) {
            v.put(String.valueOf(e.getKey()), e.getValue());
        }
        if (extra != null) {
            v.putAll(extra);
        }
        return v;
    }

    // ------------------------------------------------------------ homeContent

    public Items.HomeResult homeContent() {
        Items.HomeResult res = new Items.HomeResult();
        if (SiteConfig.TYPE_STATIC.equals(site.type)) {
            fillStaticHome(res);
            return res;
        }
        Map<String, Object> rule = site.rule("home");
        Map<String, Object> ctx = vars(null);
        Object doc = fetch(rule, ctx);
        if (doc == null) {
            return res;
        }
        if ("m3u".equals(modeOf(rule, rule)) && doc instanceof String) {
            // IPTV 索引类站点：直接把 m3u 文本转成频道卡片
            int limit = Json.intg(rule.get("limit"), 200);
            res.list.addAll(M3uParser.toItems((String) doc, limit, site.key));
            java.util.Set<String> groups = new java.util.LinkedHashSet<String>();
            for (Items.VideoItem v : res.list) {
                if (v.typeName != null && !v.typeName.isEmpty()) {
                    groups.add(v.typeName);
                }
            }
            res.classes.add(new Items.ClassItem("", "全部"));
            for (String g : groups) {
                res.classes.add(new Items.ClassItem(g, g));
            }
            return res;
        }
        Map<String, Object> classRule = Json.obj(rule.get("classes"));
        if (classRule != null) {
            String mode = modeOf(classRule, rule);
            for (Object row : rows(doc, classRule, mode)) {
                Map<String, String> f = fields(classRule, row, ctx);
                String id = f.get("type_id");
                String name = f.get("type_name");
                if (id == null) {
                    id = f.get("id");
                }
                if (name == null) {
                    name = f.get("name");
                }
                if (id != null && name != null) {
                    res.classes.add(new Items.ClassItem(id, name));
                }
            }
        }
        Map<String, Object> listRule = Json.obj(rule.get("list"));
        if (listRule == null) {
            listRule = Json.obj(rule.get("videos"));
        }
        if (listRule != null) {
            String mode = modeOf(listRule, rule);
            for (Object row : rows(doc, listRule, mode)) {
                res.list.add(toVideo(fields(listRule, row, ctx)));
            }
        }
        return res;
    }

    private void fillStaticHome(Items.HomeResult res) {
        Map<String, Object> sd = site.staticData;
        List<Object> cls = Json.arr(sd.get("classes"));
        if (cls != null) {
            for (Object o : cls) {
                Map<String, Object> m = Json.obj(o);
                if (m != null) {
                    res.classes.add(new Items.ClassItem(
                            Json.str(m.get("type_id"), ""), Json.str(m.get("type_name"), "")));
                }
            }
        }
        List<Object> list = Json.arr(sd.get("list"));
        if (list != null) {
            for (Object o : list) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) {
                    continue;
                }
                Items.VideoItem v = new Items.VideoItem();
                v.vodId = Json.str(m.get("vod_id"), "");
                v.vodName = Json.str(m.get("vod_name"), "");
                v.vodPic = Json.str(m.get("vod_pic"), "");
                v.vodRemarks = Json.str(m.get("vod_remarks"), "");
                v.typeName = Json.str(m.get("type_name"), "");
                v.vodContent = Json.str(m.get("vod_content"), "");
                v.siteKey = site.key;
                v.siteName = site.name;
                res.list.add(v);
            }
        }
    }

    // -------------------------------------------------------- categoryContent

    public List<Items.VideoItem> categoryContent(String tid, int page, Map<String, String> extend) {
        List<Items.VideoItem> out = new ArrayList<Items.VideoItem>();
        if (SiteConfig.TYPE_STATIC.equals(site.type)) {
            List<Object> list = Json.arr(site.staticData.get("list"));
            if (list != null) {
                for (Object o : list) {
                    Map<String, Object> m = Json.obj(o);
                    if (m == null) {
                        continue;
                    }
                    String typeId = Json.str(m.get("type_id"), "");
                    if (!tid.isEmpty() && !tid.equals(typeId)) {
                        continue;
                    }
                    Items.VideoItem v = new Items.VideoItem();
                    v.vodId = Json.str(m.get("vod_id"), "");
                    v.vodName = Json.str(m.get("vod_name"), "");
                    v.vodPic = Json.str(m.get("vod_pic"), "");
                    v.vodRemarks = Json.str(m.get("vod_remarks"), "");
                    v.vodContent = Json.str(m.get("vod_content"), "");
                    v.typeName = Json.str(m.get("type_name"), "");
                    v.siteKey = site.key;
                    v.siteName = site.name;
                    out.add(v);
                }
            }
            return out;
        }
        Map<String, Object> rule = site.rule("category");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("tid", tid == null ? "" : tid);
        extra.put("page", String.valueOf(page));
        if (extend != null) {
            for (Map.Entry<String, String> e : extend.entrySet()) {
                extra.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> ctx = vars(extra);
        Object doc = fetch(rule, ctx);
        if (doc == null) {
            return out;
        }
        String mode = modeOf(rule, rule);
        if ("m3u".equals(mode) && doc instanceof String) {
            int limit = Json.intg(rule.get("limit"), 200);
            for (Items.VideoItem v : M3uParser.toItems((String) doc, limit, site.key)) {
                if (tid == null || tid.isEmpty() || tid.equals(v.typeName)) {
                    out.add(v);
                }
            }
            return out;
        }
        for (Object row : rows(doc, rule, mode)) {
            out.add(toVideo(fields(rule, row, ctx)));
        }
        return out;
    }

    // ----------------------------------------------------------- detailContent

    public Items.VodDetail detailContent(String id) {
        Items.VodDetail d = new Items.VodDetail();
        d.siteKey = site.key;
        d.vodId = id == null ? "" : id;
        if (SiteConfig.TYPE_STATIC.equals(site.type)) {
            List<Object> list = Json.arr(site.staticData.get("list"));
            if (list != null) {
                for (Object o : list) {
                    Map<String, Object> m = Json.obj(o);
                    if (m == null || !id.equals(Json.str(m.get("vod_id"), ""))) {
                        continue;
                    }
                    d.vodName = Json.str(m.get("vod_name"), "");
                    d.vodPic = Json.str(m.get("vod_pic"), "");
                    d.vodContent = Json.str(m.get("vod_content"), "");
                    d.vodRemarks = Json.str(m.get("vod_remarks"), "");
                    d.typeName = Json.str(m.get("type_name"), "");
                    d.playFrom.add("direct");
                    d.playUrl.add("播放$" + Json.str(m.get("vod_play_url"), ""));
                    return d;
                }
            }
            return d;
        }
        Map<String, Object> rule = site.rule("detail");
        Map<String, Object> ctx = vars(single("id", id));
        Object doc = fetch(rule, ctx);
        if (doc == null) {
            return d;
        }
        String mode = modeOf(rule, rule);
        List<Object> rs = rows(doc, rule, mode);
        Object row = rs.isEmpty() ? doc : rs.get(0);
        Map<String, String> f = fields(rule, row, ctx);
        d.vodName = first(f, "", "vod_name", "name", "title");
        d.vodPic = first(f, "", "vod_pic", "pic", "cover");
        d.vodActor = first(f, "", "vod_actor", "actor");
        d.vodDirector = first(f, "", "vod_director", "director");
        d.vodContent = first(f, "", "vod_content", "content", "desc");
        d.vodRemarks = first(f, "", "vod_remarks", "remarks");
        d.vodYear = first(f, "", "vod_year", "year");
        d.vodArea = first(f, "", "vod_area", "area");
        d.typeName = first(f, "", "type_name", "type");
        String from = first(f, "", "vod_play_from", "play_from", "from");
        String url = first(f, "", "vod_play_url", "play_url", "url");
        if (!from.isEmpty()) {
            for (String s : from.split("\\$\\$")) {
                if (!s.trim().isEmpty()) {
                    d.playFrom.add(s.trim());
                }
            }
        }
        if (!url.isEmpty()) {
            for (String s : url.split("\\$\\$")) {
                d.playUrl.add(s.trim());
            }
        }
        if (d.playFrom.isEmpty()) {
            d.playFrom.add("direct");
        }
        if (d.playUrl.isEmpty() && !url.isEmpty()) {
            d.playUrl.add("播放$" + url);
        }
        return d;
    }

    // ----------------------------------------------------------- searchContent

    public List<Items.VideoItem> searchContent(String keyword, boolean quick) {
        List<Items.VideoItem> out = new ArrayList<Items.VideoItem>();
        if (SiteConfig.TYPE_STATIC.equals(site.type)) {
            List<Object> list = Json.arr(site.staticData.get("list"));
            if (list != null) {
                String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
                for (Object o : list) {
                    Map<String, Object> m = Json.obj(o);
                    if (m == null) {
                        continue;
                    }
                    String name = Json.str(m.get("vod_name"), "");
                    if (kw.isEmpty() || name.toLowerCase(Locale.ROOT).contains(kw)) {
                        Items.VideoItem v = new Items.VideoItem();
                        v.vodId = Json.str(m.get("vod_id"), "");
                        v.vodName = name;
                        v.vodPic = Json.str(m.get("vod_pic"), "");
                        v.vodRemarks = Json.str(m.get("vod_remarks"), "");
                        v.siteKey = site.key;
                        v.siteName = site.name;
                        out.add(v);
                    }
                }
            }
            return out;
        }
        if (!site.searchable) {
            return out;
        }
        Map<String, Object> rule = site.rule("search");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("wd", keyword == null ? "" : keyword);
        extra.put("keyword", keyword == null ? "" : keyword);
        extra.put("page", "1");
        Map<String, Object> ctx = vars(extra);
        Object doc = fetch(rule, ctx);
        if (doc == null) {
            return out;
        }
        String mode = modeOf(rule, rule);
        for (Object row : rows(doc, rule, mode)) {
            out.add(toVideo(fields(rule, row, ctx)));
        }
        return out;
    }

    // ----------------------------------------------------------- playerContent

    public Items.PlayResult playerContent(String flag, String id) {
        Items.PlayResult pr = new Items.PlayResult();
        if (id == null || id.isEmpty()) {
            pr.message = "空的播放标识";
            return pr;
        }
        for (Map.Entry<String, String> e : site.headers.entrySet()) {
            pr.headers.put(e.getKey(), e.getValue());
        }
        Map<String, Object> rule = site.rule("play");
        Map<String, Object> ctx = vars(single("id", id));
        ctx.put("flag", flag == null ? "" : flag);

        Map<String, String> ruleHeaders = headerMap(rule, ctx);
        pr.headers.putAll(ruleHeaders);

        // 1) 若配置了二次请求，则先取真实地址
        String reqUrl = Expr.render(Json.str(rule.get("url"), ""), ctx);
        if (!reqUrl.isEmpty()) {
            try {
                Http.Response resp = (rule.containsKey("body") || "POST".equalsIgnoreCase(Json.str(rule.get("method"), "GET")))
                        ? http.post(reqUrl, pr.headers, bodyOf(rule, ctx))
                        : http.get(reqUrl, pr.headers);
                if (!resp.ok()) {
                    pr.message = "取播放地址失败: HTTP " + resp.code;
                    return pr;
                }
                Object doc = parseDoc(resp.body(), modeOf(rule, rule));
                String fieldSpec = Json.str(rule.get("url_field"), "$.url");
                FieldSpec fs = FieldSpec.of(fieldSpec);
                String got = fs == null ? "" : fs.extract(doc, ctx);
                if (got.isEmpty()) {
                    pr.message = "未能从响应中提取播放地址";
                    return pr;
                }
                pr.url = absolutize(got, reqUrl);
            } catch (SecurityException se) {
                pr.message = se.getMessage();
                return pr;
            } catch (RuntimeException e) {
                pr.message = "取播放地址异常: " + e.getMessage();
                return pr;
            }
        } else {
            pr.url = id;
        }

        // 2) 地址兜底校验：必须是 http(s)，且通过安全守卫
        String why = guardReason(pr.url);
        if (why != null) {
            pr.message = "播放地址被安全策略拒绝: " + why;
            pr.url = "";
            return pr;
        }
        pr.format = guessFormat(pr.url);
        if (!pr.headers.containsKey("Referer") && !site.api.isEmpty()) {
            pr.headers.put("Referer", site.api + "/");
        }
        return pr;
    }

    private String guardReason(String url) {
        return http.guard().reason(url);
    }

    private String absolutize(String maybeRelative, String base) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative;
        }
        return M3uParser.resolve(base, maybeRelative);
    }

    private static String guessFormat(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".m3u8")) {
            return "m3u8";
        }
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mkv") || path.endsWith(".webm")) {
            return "mp4";
        }
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".flac")) {
            return "audio";
        }
        return "auto";
    }

    // ---------------------------------------------------------------- 内部工具

    private static Map<String, Object> single(String k, String v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v == null ? "" : v);
        return m;
    }

    private String modeOf(Map<String, Object> rule, Map<String, Object> fallback) {
        String m = Json.str(rule.get("parse"), "");
        if (m.isEmpty() && fallback != null) {
            m = Json.str(fallback.get("parse"), "json");
        }
        m = m.toLowerCase(Locale.ROOT);
        if (m.equals("html") || m.equals("xpath")) {
            return "html";
        }
        if (m.equals("regex") || m.equals("re")) {
            return "regex";
        }
        if (m.equals("m3u")) {
            return "m3u";
        }
        return "json";
    }

    /** 按规则发起请求并解析成文档对象。 */
    private Object fetch(Map<String, Object> rule, Map<String, Object> ctx) {
        if (rule == null || rule.isEmpty()) {
            return null;
        }
        String url = Expr.render(Json.str(rule.get("url"), ""), ctx);
        if (url.isEmpty()) {
            // 无 URL：把 api 当作兜底
            url = site.api;
            if (url.isEmpty()) {
                return null;
            }
        }
        Map<String, String> headers = headerMap(rule, ctx);
        String method = Json.str(rule.get("method"), "GET").toUpperCase(Locale.ROOT);
        String body = "POST".equals(method) ? bodyOf(rule, ctx) : null;
        Http.Response resp = ("POST".equals(method))
                ? http.post(url, headers, body)
                : http.get(url, headers);
        if (!resp.ok()) {
            throw new RuntimeException("请求失败 HTTP " + resp.code + " " + SecurityGuard.safeLog(url));
        }
        return parseDoc(resp.body(), modeOf(rule, rule));
    }

    private Object parseDoc(String body, String mode) {
        if (body == null) {
            return null;
        }
        if ("html".equals(mode)) {
            return HtmlParser.parse(body);
        }
        if ("json".equals(mode)) {
            try {
                return Json.parse(body);
            } catch (RuntimeException e) {
                // 有些站点返回 JSONP：尝试截取第一个 { 或 [ 之后的内容
                int a = body.indexOf('{');
                int b = body.indexOf('[');
                int st = -1;
                if (a >= 0 && b >= 0) {
                    st = Math.min(a, b);
                } else {
                    st = Math.max(a, b);
                }
                if (st < 0) {
                    return null;
                }
                int last = Math.max(body.lastIndexOf('}'), body.lastIndexOf(']'));
                String cut = last > st ? body.substring(st, last + 1) : body.substring(st);
                return Json.parse(cut);
            }
        }
        return body; // regex / m3u 模式直接用字符串
    }

    private Map<String, String> headerMap(Map<String, Object> rule, Map<String, Object> ctx) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : Expr.renderStringMap(site.headers, ctx).entrySet()) {
            h.put(e.getKey(), e.getValue());
        }
        Map<String, Object> rh = Json.obj(rule.get("headers"));
        if (rh != null) {
            for (Map.Entry<String, String> e : Expr.renderMap(rh, ctx).entrySet()) {
                h.put(e.getKey(), e.getValue());
            }
        }
        return h;
    }

    private String bodyOf(Map<String, Object> rule, Map<String, Object> ctx) {
        Object b = rule.get("body");
        if (b == null) {
            return "";
        }
        Map<String, Object> bm = Json.obj(b);
        if (bm != null) {
            Map<String, String> rendered = Expr.renderMap(bm, ctx);
            return Http.encodeForm(rendered);
        }
        return Expr.render(Json.str(b, ""), ctx);
    }

    /** 取列表行 */
    private List<Object> rows(Object doc, Map<String, Object> rule, String mode) {
        String root = Json.str(rule.get("root"), "");
        if (doc == null) {
            return Collections.emptyList();
        }
        List<Object> out = new ArrayList<Object>();
        if (root.isEmpty()) {
            if (doc instanceof List) {
                return castList(doc);
            }
            out.add(doc);
            return out;
        }
        if ("html".equals(mode)) {
            if (!(doc instanceof HtmlParser.Node)) {
                return out;
            }
            List<HtmlParser.Node> nodes = root.startsWith("css:")
                    ? com.tvbox.core.html.CssSelector.compile(root.substring(4)).eval((HtmlParser.Node) doc)
                    : com.tvbox.core.html.CssSelector.compile(root).eval((HtmlParser.Node) doc);
            out.addAll(nodes);
            return out;
        }
        if ("regex".equals(mode)) {
            List<String> all = com.tvbox.core.util.Text.findAll(String.valueOf(doc), root, 0);
            out.addAll(all);
            return out;
        }
        Object found = JsonPath.compile(root.startsWith("$") ? root : "$." + root).eval(doc);
        if (found instanceof List) {
            return castList(found);
        }
        if (found != null) {
            out.add(found);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return new ArrayList<Object>((List<Object>) o);
    }

    /** 按 fields 从一行里抽出所有字段 */
    private Map<String, String> fields(Map<String, Object> rule, Object row, Map<String, Object> ctx) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Map<String, Object> fs = Json.obj(rule.get("fields"));
        if (fs == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : fs.entrySet()) {
            String spec = Json.str(e.getValue(), "");
            FieldSpec f = FieldSpec.of(spec);
            if (f == null) {
                continue;
            }
            String val = f.extract(row, ctx);
            if (!val.isEmpty()) {
                out.put(e.getKey(), val.trim());
            }
        }
        return out;
    }

    private Items.VideoItem toVideo(Map<String, String> f) {
        Items.VideoItem v = new Items.VideoItem();
        v.vodId = first(f, "", "vod_id", "id", "url");
        v.vodName = first(f, "", "vod_name", "name", "title");
        v.vodPic = first(f, "", "vod_pic", "pic", "cover", "img");
        v.vodRemarks = first(f, "", "vod_remarks", "remarks", "note");
        v.vodYear = first(f, "", "vod_year", "year");
        v.vodArea = first(f, "", "vod_area", "area");
        v.vodActor = first(f, "", "vod_actor", "actor");
        v.vodContent = first(f, "", "vod_content", "content", "desc");
        v.typeName = first(f, "", "type_name", "type");
        v.siteKey = site.key;
        v.siteName = site.name;
        return v;
    }

    private static String first(Map<String, String> f, String def, String... keys) {
        for (String k : keys) {
            String v = f.get(k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return def;
    }
}
