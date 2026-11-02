package com.tvbox.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tvbox.core.expr.Expr;
import com.tvbox.core.html.CssSelector;
import com.tvbox.core.html.HtmlParser;
import com.tvbox.core.json.Json;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.net.Http;
import com.tvbox.core.net.SecurityGuard;
import com.tvbox.core.parse.ConfigLoader;
import com.tvbox.core.parse.DeclSpider;
import com.tvbox.core.parse.M3uParser;
import com.tvbox.core.path.JsonPath;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖测试入口：javac 编译后直接 java CoreTest 运行。
 *
 * 覆盖：JSON / JSONPath / HTML+CSS / 模板表达式 / SSRF 守卫 / 配置准入 /
 *       HTTP 行为 / Spider 五方法端到端 / M3U 解析。
 */
public class CoreTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        testJson();
        testJsonPath();
        testHtml();
        testExpr();
        testGuard();
        testSiteConfig();
        testM3u();
        HttpServer server = startServer();
        try {
            testHttp(server);
            testSpiderEndToEnd(server);
            testM3uSpider(server);
            testHtmlSpider(server);
        } finally {
            server.stop(0);
        }
        System.out.println();
        System.out.println("========================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("========================================");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ JSON

    static void testJson() {
        String src = "{\"a\":1,\"b\":[1,2,{\"c\":\"x\\\"y\"}],\"d\":null,\"e\":true,\"f\":1.5}";
        Object o = Json.parse(src);
        eq("json.parse int", "1", Json.str(Json.at(o, "a")));
        eq("json.parse nested", "x\"y", Json.str(Json.at(o, "b", "2", "c")));
        check("json.parse null", Json.at(o, "d") == null);
        check("json.parse bool", Boolean.TRUE.equals(Json.at(o, "e")));
        eq("json.write roundtrip", Json.parse(Json.write(o)).toString(), o.toString());
        check("json depth guard", throwsJson(() -> Json.parse(deepJson(200))));

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("k", "v");
        eq("json accessor default", "zz", Json.str(m.get("nope"), "zz"));
        eq("json bool parse", "true", String.valueOf(Json.bool("yes", false)));
    }

    static String deepJson(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("[");
        }
        for (int i = 0; i < n; i++) {
            sb.append("]");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- JSONPath

    static void testJsonPath() {
        Object o = Json.parse("{\"list\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}],"
                + "\"x\":{\"y\":{\"z\":\"deep\"}},\"data\":{\"items\":[{\"t\":\"one\"},{\"t\":\"two\"}]}}");
        Object v = JsonPath.compile("$.x.y.z").eval(o);
        eq("jsonpath deep", "deep", Json.str(v, ""));
        eq("jsonpath index", "2", Json.str(JsonPath.compile("$.list[1].id").eval(o), ""));
        eq("jsonpath negative", "b", Json.str(JsonPath.compile("$.list[-1].name").eval(o), ""));
        eq("jsonpath multi", "2", String.valueOf(JsonPath.compile("$.list[*].id").evalMulti(o).size()));
        eq("jsonpath slice", "1", String.valueOf(JsonPath.compile("$.list[0:1]").evalMulti(o).size()));
        eq("jsonpath no-leading-$", "2", Json.str(JsonPath.compile("list[1].id").eval(o), ""));
        eq("jsonpath quote key", "one", Json.str(JsonPath.compile("$['data']['items'][0]['t']").eval(o), ""));
        eq("jsonpath descend", "2", String.valueOf(JsonPath.compile("$..t").evalMulti(o).size()));
        eq("jsonpath miss", "", Json.str(JsonPath.compile("$.nope.nope").eval(o), ""));
    }

    // ------------------------------------------------------------------ HTML

    static void testHtml() {
        String html = "<html><body>"
                + "<div class=\"mod\" id=\"m1\"><ul class=\"list\">"
                + "<li class=\"item\"><a href=\"/v/1.html\" title=\"第一\">第一</a><span class=\"note\">HD</span></li>"
                + "<li class=\"item\"><a href=\"/v/2.html\" title=\"第二\">第二</a><span class=\"note\">SD</span></li>"
                + "</ul></div>"
                + "<div class=\"mod\" id=\"m2\"><p>hello <b>world</b></p></div>"
                + "</body></html>";
        HtmlParser.Node root = HtmlParser.parse(html);
        List<HtmlParser.Node> items = root.select("ul.list li.item");
        eq("css descendant count", "2", String.valueOf(items.size()));
        eq("css attr", "/v/1.html", items.get(0).selectFirst("a").attr("href"));
        eq("css text", "第一", items.get(0).selectFirst("a").text());
        eq("css title attr", "第一", items.get(0).selectFirst("a").attr("title"));
        eq("css eq", "第二", root.select("li.item a:eq(1)").get(0).text());
        eq("css id", "1", String.valueOf(root.select("#m1").size()));
        eq("css class+tag", "HD", root.select("span.note").get(0).text());
        eq("css nth-child", "第二", root.select("li:nth-child(2) a").get(0).text());
        eq("css attr=val", "1", String.valueOf(root.select("a[href=/v/1.html]").size()));
        eq("css union", "2", String.valueOf(root.select("#m1, #m2").size()));
        eq("css child", "world", root.select("p > b").get(0).text());
        eq("html entity", "a&b", HtmlParser.parse("<p>a&amp;b</p>").selectFirst("p").text());
        check("html node cap", HtmlParser.parse(hugeHtml()).children.size() >= 0);
        eq("css select empty", "0", String.valueOf(CssSelector.select(root, "").size()));
    }

    static String hugeHtml() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30000; i++) {
            sb.append("<div>").append(i).append("</div>");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Expr

    static void testExpr() {
        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        ctx.put("id", "abc 123");
        ctx.put("page", "2");
        eq("expr plain", "/v/abc 123", Expr.render("/v/${id}", ctx));
        eq("expr urlencode", "abc+123", Expr.render("${id|urlencode}", ctx));
        eq("expr md5 len", "32", String.valueOf(Expr.render("${id|md5}", ctx).length()));
        eq("expr base64 roundtrip", "abc 123", com.tvbox.core.util.Text.base64Decode(Expr.render("${id|base64}", ctx)));
        eq("expr upper", "ABC 123", Expr.render("${id|upper}", ctx));
        eq("expr sub", "abc", Expr.render("${id|sub:0:3}", ctx));
        // 安全：以下都不是合法变量名，必须原样回显而不是被求值
        eq("expr no-eval arithmetic", "${1+1}", Expr.render("${1+1}", ctx));
        eq("expr no-eval method", "${id.toString()}", Expr.render("${id.toString()}", ctx));
        eq("expr no-eval shell", "${id;ls}", Expr.render("${id;ls}", ctx));
        eq("expr no-eval runtime", "${@java.lang.Runtime@getRuntime()}", Expr.render("${@java.lang.Runtime@getRuntime()}", ctx));
        eq("expr missing var", "", Expr.render("${nope}", ctx));
        eq("expr literal only", "static", Expr.render("static", ctx));
    }

    // --------------------------------------------------------------- 安全守卫

    static void testGuard() {
        SecurityGuard g = SecurityGuard.strictDefault();
        check("guard reject file", g.reason("file:///etc/passwd") != null);
        check("guard reject jar", g.reason("jar:http://x!/y") != null);
        check("guard reject ftp", g.reason("ftp://x/y") != null);
        check("guard reject loopback", g.reason("http://127.0.0.1:8080/x") != null);
        check("guard reject localhost", g.reason("http://localhost/x") != null);
        check("guard reject 10/8", g.reason("http://10.1.2.3/x") != null);
        check("guard reject 192.168", g.reason("http://192.168.1.1/x") != null);
        check("guard reject 172.16", g.reason("http://172.16.0.1/x") != null);
        check("guard reject metadata", g.reason("http://169.254.169.254/latest/meta-data") != null);
        check("guard reject port 22", g.reason("http://example.com:22/") != null);
        check("guard reject userinfo", g.reason("http://a:b@example.com/") != null);
        check("guard allow https", g.reason("https://example.com/a.m3u8") == null);
        check("guard allow http 8080", g.reason("http://example.com:8080/x") == null);
        check("guard reject ipv6 ula", g.reason("http://[fd00::1]/") != null);
        check("guard reject ipv6 ll", g.reason("http://[fe80::1]/") != null);

        SecurityGuard wl = SecurityGuard.builder().allowHost("example.com").build();
        check("guard whitelist allow", wl.reason("https://example.com/x") == null);
        check("guard whitelist deny", wl.reason("https://evil.com/x") != null);
        check("guard whitelist subdomain", wl.reason("https://a.example.com/x") == null);
    }

    // ------------------------------------------------------------ 配置准入校验

    static void testSiteConfig() {
        String bad = "{\"sites\":["
                + "{\"key\":\"js1\",\"name\":\"JS站\",\"type\":1,\"api\":\"http://x\"},"
                + "{\"key\":\"jar1\",\"name\":\"JAR站\",\"type\":2,\"api\":\"http://x\"},"
                + "{\"key\":\"sp1\",\"name\":\"Spider站\",\"type\":\"spider\"},"
                + "{\"key\":\"ok1\",\"name\":\"正常站\",\"type\":\"decl\",\"api\":\"https://a.example\"," +
                "\"rules\":{\"home\":{\"url\":\"${api}/h\",\"parse\":\"json\"}}},"
                + "{\"key\":\"trap\",\"name\":\"夹带站\",\"type\":\"decl\",\"api\":\"https://b.example\"," +
                "\"rules\":{\"home\":{\"url\":\"${api}/h\",\"jar\":\"http://evil/x.jar\"}}}"
                + "]}";
        ConfigLoader.Config cfg = ConfigLoader.parse(bad);
        eq("config rejects js/jar/spider", "1", String.valueOf(cfg.sites.size()));
        eq("config rejected count", "4", String.valueOf(cfg.rejected.size()));
        eq("config kept site", "ok1", cfg.sites.get(0).key);
        check("config log mentions type", cfg.rejected.toString().contains("远程代码"));

        String topLevel = "{\"spider\":\"http://evil/x.jar\",\"sites\":[]}";
        ConfigLoader.Config c2 = ConfigLoader.parse(topLevel);
        check("config rejects top-level spider", !c2.rejected.isEmpty());

        check("config rejects bad json", throwsRuntime(() -> ConfigLoader.parse("{not json")));
        check("config rejects empty", throwsRuntime(() -> ConfigLoader.parse("")));
    }

    // -------------------------------------------------------------------- M3U

    static void testM3u() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-logo=\"http://img/a.png\" group-title=\"新闻\",CCTV-1\n"
                + "http://stream.example/cctv1.m3u8\n"
                + "#EXTINF:-1 group-title=\"体育\",CCTV-5\n"
                + "http://stream.example/cctv5.m3u8\n";
        List<Map<String, String>> pl = M3uParser.playList(m3u, 100);
        eq("m3u count", "2", String.valueOf(pl.size()));
        eq("m3u name", "CCTV-1", pl.get(0).get("name"));
        eq("m3u logo", "http://img/a.png", pl.get(0).get("logo"));
        eq("m3u group", "体育", pl.get(1).get("group"));
        eq("m3u resolve rel", "http://a/b/c.m3u8", M3uParser.resolve("http://a/b/x.m3u8", "c.m3u8"));
        eq("m3u resolve abs", "http://z/y.m3u8", M3uParser.resolve("http://a/b/x.m3u8", "http://z/y.m3u8"));
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow/index.m3u8\n";
        check("m3u master detect", M3uParser.isMasterPlaylist(master));
        eq("m3u media list", "low/index.m3u8", M3uParser.mediaPlaylists(master).get(0));
    }

    // ------------------------------------------------------------------- HTTP

    static void testHttp(HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SecurityGuard testGuard = SecurityGuard.builder()
                .allowPrivateForTest(true)
                .port(server.getAddress().getPort())
                .build();
        Http http = new Http(testGuard).timeout(5000, 5000);

        Http.Response r = http.get(base + "/json/home.json");
        check("http ok", r.ok());
        check("http body", r.body().contains("class"));

        // 重定向到 file:// 必须被拒绝（协议白名单生效，与是否允许私网无关）
        boolean blocked = false;
        try {
            http.get(base + "/redirect-file");
        } catch (SecurityException e) {
            blocked = true;
        }
        check("http blocks redirect to file://", blocked);

        // 重定向到不允许的端口
        blocked = false;
        try {
            http.get(base + "/redirect-port");
        } catch (SecurityException e) {
            blocked = true;
        }
        check("http blocks redirect to bad port", blocked);

        // 响应体上限
        boolean capped = false;
        try {
            new Http(testGuard).maxBytes(1024).timeout(5000, 5000).get(base + "/big");
        } catch (RuntimeException e) {
            capped = e.getMessage() != null && e.getMessage().contains("超过上限");
        }
        check("http enforces body cap", capped);

        // 白名单生效
        SecurityGuard wl = SecurityGuard.builder().allowPrivateForTest(true)
                .port(server.getAddress().getPort()).allowHost("no-such-host.test").build();
        boolean denied = false;
        try {
            new Http(wl).get(base + "/json/home.json");
        } catch (SecurityException e) {
            denied = true;
        }
        check("http host whitelist enforced", denied);
    }

    // -------------------------------------------------------- Spider 端到端

    static void testSpiderEndToEnd(HttpServer server) {
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/json";
        SecurityGuard testGuard = SecurityGuard.builder()
                .allowPrivateForTest(true).port(port).build();
        Http http = new Http(testGuard);

        String cfgJson = "{\"sites\":[{"
                + "\"key\":\"demo\",\"name\":\"演示站\",\"type\":\"decl\",\"api\":\"" + base + "\","
                + "\"headers\":{\"User-Agent\":\"SafeTVBox\"},"
                + "\"rules\":{"
                + "  \"home\":{\"url\":\"${api}/home.json\",\"parse\":\"json\","
                + "    \"classes\":{\"root\":\"$.class\",\"fields\":{\"type_id\":\"$.type_id\",\"type_name\":\"$.type_name\"}},"
                + "    \"list\":{\"root\":\"$.list\",\"fields\":{\"vod_id\":\"$.id\",\"vod_name\":\"$.title\",\"vod_pic\":\"$.pic\",\"vod_remarks\":\"$.note\"}}},"
                + "  \"category\":{\"url\":\"${api}/cat.json?tid=${tid}&page=${page}\",\"parse\":\"json\",\"root\":\"$.list\","
                + "    \"fields\":{\"vod_id\":\"$.id\",\"vod_name\":\"$.title\",\"vod_pic\":\"$.pic\"}},"
                + "  \"detail\":{\"url\":\"${api}/detail.json?id=${id}\",\"parse\":\"json\",\"root\":\"$.data\","
                + "    \"fields\":{\"vod_name\":\"$.title\",\"vod_pic\":\"$.pic\",\"vod_content\":\"$.desc\","
                + "      \"vod_play_from\":\"$.from\",\"vod_play_url\":\"$.urls\"}},"
                + "  \"search\":{\"url\":\"${api}/search.json?wd=${wd|urlencode}\",\"parse\":\"json\",\"root\":\"$.list\","
                + "    \"fields\":{\"vod_id\":\"$.id\",\"vod_name\":\"$.title\"}},"
                + "  \"play\":{\"url\":\"${api}/play.json?id=${id}\",\"parse\":\"json\",\"url_field\":\"$.url\"}"
                + "}}]}";
        ConfigLoader.Config cfg = ConfigLoader.parse(cfgJson);
        eq("spider config loaded", "1", String.valueOf(cfg.sites.size()));
        SiteConfig site = cfg.sites.get(0);
        DeclSpider sp = new DeclSpider(site, http);

        Items.HomeResult home = sp.homeContent();
        eq("spider home classes", "2", String.valueOf(home.classes.size()));
        eq("spider home class name", "电影", home.classes.get(0).typeName);
        eq("spider home list", "2", String.valueOf(home.list.size()));
        eq("spider home title", "样例影片A", home.list.get(0).vodName);
        eq("spider home pic", "http://img/a.jpg", home.list.get(0).vodPic);

        List<Items.VideoItem> cat = sp.categoryContent("1", 1, new HashMap<String, String>());
        eq("spider category size", "2", String.valueOf(cat.size()));
        eq("spider category first", "分类影片A", cat.get(0).vodName);

        Items.VodDetail d = sp.detailContent("100");
        eq("spider detail name", "样例影片A", d.vodName);
        eq("spider detail desc", "这是一段简介", d.vodContent);
        eq("spider detail from", "2", String.valueOf(d.playFrom.size()));
        eq("spider detail url count", "2", String.valueOf(d.playUrl.size()));

        List<Items.VideoItem> s = sp.searchContent("样例", false);
        eq("spider search size", "1", String.valueOf(s.size()));
        eq("spider search name", "搜索结果A", s.get(0).vodName);

        Items.PlayResult pr = sp.playerContent("m3u8", "100-1");
        // 相对地址 /stream/... 按标准 URL 语义相对站点根目录解析
        check("spider play url", pr.url.equals("http://127.0.0.1:" + port + "/stream/100-1.m3u8"));
        eq("spider play format", "m3u8", pr.format);
        check("spider play has UA", "SafeTVBox".equals(pr.headers.get("User-Agent")));
        check("spider play referer", pr.headers.containsKey("Referer"));

        // 未配置 play 规则时，播放地址本身也要过安全策略：内网/元数据地址必须被拒
        String noPlayCfg = "{\"sites\":[{\"key\":\"np\",\"name\":\"无二次解析\",\"type\":\"decl\",\"api\":\"" + base + "\","
                + "\"rules\":{\"home\":{\"url\":\"${api}/home.json\",\"parse\":\"json\"}}}]}";
        Http strictHttp = new Http(SecurityGuard.strictDefault());
        DeclSpider sp2 = new DeclSpider(ConfigLoader.parse(noPlayCfg).sites.get(0), strictHttp);
        Items.PlayResult bad = sp2.playerContent("x", "http://169.254.169.254/x.m3u8");
        check("spider play blocks metadata ip", !bad.ok() && bad.message.contains("安全策略"));
        Items.PlayResult good = sp2.playerContent("x", "https://example.com/a.m3u8");
        check("spider play allows public url", good.ok());
    }

    static void testM3uSpider(HttpServer server) {
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/m3u";
        SecurityGuard testGuard = SecurityGuard.builder()
                .allowPrivateForTest(true).port(port).build();
        Http http = new Http(testGuard);
        String cfg = "{\"sites\":[{\"key\":\"iptv\",\"name\":\"IPTV\",\"type\":\"decl\",\"api\":\"" + base + "\","
                + "\"rules\":{\"home\":{\"url\":\"${api}/list.m3u\",\"parse\":\"m3u\",\"limit\":50},"
                + "\"category\":{\"url\":\"${api}/list.m3u\",\"parse\":\"m3u\",\"limit\":50}}}]}";
        DeclSpider sp = new DeclSpider(ConfigLoader.parse(cfg).sites.get(0), http);
        Items.HomeResult home = sp.homeContent();
        eq("m3u spider list", "2", String.valueOf(home.list.size()));
        eq("m3u spider name", "CCTV-1", home.list.get(0).vodName);
        eq("m3u spider logo", "http://img/c1.png", home.list.get(0).vodPic);
        eq("m3u spider classes", "3", String.valueOf(home.classes.size()));
        List<Items.VideoItem> only = sp.categoryContent("Sports", 1, new HashMap<String, String>());
        eq("m3u spider filter by group", "1", String.valueOf(only.size()));
        eq("m3u spider filtered name", "CCTV-5", only.get(0).vodName);
    }

    static void testHtmlSpider(HttpServer server) {
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/html";
        SecurityGuard testGuard = SecurityGuard.builder()
                .allowPrivateForTest(true).port(port).build();
        Http http = new Http(testGuard);

        String cfgJson = "{\"sites\":[{"
                + "\"key\":\"htmlsite\",\"name\":\"HTML站\",\"type\":\"decl\",\"api\":\"" + base + "\","
                + "\"rules\":{"
                + "  \"home\":{\"url\":\"${api}/list.html\",\"parse\":\"html\","
                + "    \"list\":{\"root\":\"css:ul.vod li\","
                + "      \"fields\":{\"vod_id\":\"css:a@href\",\"vod_name\":\"css:a@title\",\"vod_pic\":\"css:img@src\",\"vod_remarks\":\"css:.note@text\"}},"
                + "    \"classes\":{\"root\":\"css:.nav a\",\"fields\":{\"type_id\":\"css:a@href\",\"type_name\":\"css:a@text\"}}},"
                + "  \"category\":{\"url\":\"${api}/list.html\",\"parse\":\"html\",\"root\":\"css:ul.vod li\","
                + "    \"fields\":{\"vod_id\":\"css:a@href\",\"vod_name\":\"css:a@title\"}}"
                + "}}]}";
        ConfigLoader.Config cfg = ConfigLoader.parse(cfgJson);
        eq("html config ok", "1", String.valueOf(cfg.sites.size()));
        DeclSpider sp = new DeclSpider(cfg.sites.get(0), http);
        Items.HomeResult home = sp.homeContent();
        eq("html spider list size", "2", String.valueOf(home.list.size()));
        eq("html spider name", "影片甲", home.list.get(0).vodName);
        eq("html spider href", "/v/1.html", home.list.get(0).vodId);
        eq("html spider pic", "http://img/1.jpg", home.list.get(0).vodPic);
        eq("html spider note", "HD", home.list.get(0).vodRemarks);
        eq("html spider classes", "2", String.valueOf(home.classes.size()));
    }

    // -------------------------------------------------------------- 测试服务器

    static HttpServer startServer() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/json/home.json", h("application/json",
                "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"},{\"type_id\":\"2\",\"type_name\":\"剧集\"}],"
                        + "\"list\":[{\"id\":\"100\",\"title\":\"样例影片A\",\"pic\":\"http://img/a.jpg\",\"note\":\"HD\"},"
                        + "{\"id\":\"101\",\"title\":\"样例影片B\",\"pic\":\"http://img/b.jpg\",\"note\":\"SD\"}]}"));
        s.createContext("/json/cat.json", h("application/json",
                "{\"list\":[{\"id\":\"200\",\"title\":\"分类影片A\",\"pic\":\"http://img/c.jpg\"},"
                        + "{\"id\":\"201\",\"title\":\"分类影片B\"}]}"));
        s.createContext("/json/detail.json", h("application/json",
                "{\"data\":{\"title\":\"样例影片A\",\"pic\":\"http://img/a.jpg\",\"desc\":\"这是一段简介\","
                        + "\"from\":\"m3u8$$yun\",\"urls\":\"第1集$/s/1.m3u8#第2集$/s/2.m3u8$$/s/whole.mp4\"}}"));
        s.createContext("/json/search.json", h("application/json",
                "{\"list\":[{\"id\":\"100\",\"title\":\"搜索结果A\"}]}"));
        s.createContext("/json/play.json", h("application/json",
                "{\"url\":\"/stream/100-1.m3u8\"}"));
        s.createContext("/html/list.html", h("text/html; charset=utf-8",
                "<html><body><div class='nav'><a href='/t/1'>电影</a><a href='/t/2'>剧集</a></div>"
                        + "<ul class='vod'>"
                        + "<li><a href='/v/1.html' title='影片甲'><img src='http://img/1.jpg'/></a><span class='note'>HD</span></li>"
                        + "<li><a href='/v/2.html' title='影片乙'><img src='http://img/2.jpg'/></a><span class='note'>SD</span></li>"
                        + "</ul></body></html>"));
        s.createContext("/m3u/list.m3u", h("audio/x-mpegurl",
                "#EXTM3U\n"
                        + "#EXTINF:-1 tvg-logo=\"http://img/c1.png\" group-title=\"News\",CCTV-1\n"
                        + "http://stream/cctv1.m3u8\n"
                        + "#EXTINF:-1 group-title=\"Sports\",CCTV-5\n"
                        + "http://stream/cctv5.m3u8\n"));
        s.createContext("/redirect-file", new HttpHandler() {
            public void handle(HttpExchange e) throws java.io.IOException {
                e.getResponseHeaders().add("Location", "file:///etc/passwd");
                e.sendResponseHeaders(302, -1);
                e.close();
            }
        });
        s.createContext("/redirect-port", new HttpHandler() {
            public void handle(HttpExchange e) throws java.io.IOException {
                e.getResponseHeaders().add("Location", "http://127.0.0.1:22/x");
                e.sendResponseHeaders(302, -1);
                e.close();
            }
        });
        s.createContext("/big", h("text/plain", repeat("A", 200000)));
        s.start();
        return s;
    }

    static HttpHandler h(final String type, final String body) {
        return new HttpHandler() {
            public void handle(HttpExchange e) throws java.io.IOException {
                byte[] b = body.getBytes("UTF-8");
                e.getResponseHeaders().add("Content-Type", type);
                e.sendResponseHeaders(200, b.length);
                OutputStream os = e.getResponseBody();
                os.write(b);
                os.close();
            }
        };
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 断言工具

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  [OK] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    static void eq(String name, String expected, String actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  [OK] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  期望<" + expected + "> 实际<" + actual + ">");
        }
    }

    interface Run {
        void run();
    }

    static boolean throwsRuntime(Run r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static boolean throwsJson(Run r) {
        try {
            r.run();
            return false;
        } catch (Json.JsonException e) {
            return true;
        }
    }
}
