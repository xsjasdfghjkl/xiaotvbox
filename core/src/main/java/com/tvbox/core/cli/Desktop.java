package com.tvbox.core.cli;

import com.tvbox.core.json.Json;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.net.Http;
import com.tvbox.core.net.SecurityGuard;
import com.tvbox.core.parse.ConfigLoader;
import com.tvbox.core.parse.DeclSpider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 桌面命令行入口：在电脑上直接测试规则与播放，不需要 Android。
 *
 * 两种用法：
 *   1) 交互模式（推荐）：java -cp out com.tvbox.core.cli.Desktop -c 配置.json
 *   2) 单次命令：       java -cp out com.tvbox.core.cli.Desktop -c 配置.json play 站点key 播放id
 *
 * 安全策略默认与 Android 端完全一致（同一个 SecurityGuard）。
 * 只有在你显式加 --allow-private 时才会放行内网地址——用于测试自己的 NAS/局域网源。
 */
public final class Desktop {

    private static final String DEFAULT_CONFIG = "config/demo-safe.json";

    private ConfigLoader.Config cfg;
    private Http http;
    private boolean allowPrivate;
    private String lastPlayUrl = "";
    private Items.VodDetail lastDetail;
    private List<Items.VideoItem> lastList = new ArrayList<Items.VideoItem>();
    private String lastSiteKey = "";

    public static void main(String[] args) {
        try {
            new Desktop().run(args);
        } catch (Exception e) {
            System.out.println("错误: " + e.getMessage());
        }
    }

    private void run(String[] args) throws Exception {
        String configPath = DEFAULT_CONFIG;
        List<String> positional = new ArrayList<String>();
        boolean dumpOnly = false;
        boolean noPlay = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-c".equals(a) || "--config".equals(a)) {
                configPath = args[++i];
            } else if ("--allow-private".equals(a)) {
                allowPrivate = true;
            } else if ("--dump".equals(a)) {
                dumpOnly = true;
            } else if ("--no-play".equals(a)) {
                noPlay = true;
            } else {
                positional.add(a);
            }
        }

        SecurityGuard.Builder gb = SecurityGuard.builder();
        if (allowPrivate) {
            gb.allowPrivateForTest(true);
            System.out.println("!! 已开启 --allow-private：内网地址也会被放行，仅用于测试你自己的局域网源。");
        }
        SecurityGuard guard = gb.build();
        http = new Http(guard).timeout(10000, 20000);

        cfg = loadConfig(configPath);
        printConfigSummary();

        if (positional.isEmpty()) {
            repl();
            return;
        }

        String cmd = positional.get(0).toLowerCase(Locale.ROOT);
        String a1 = positional.size() > 1 ? positional.get(1) : null;
        String a2 = positional.size() > 2 ? positional.get(2) : null;

        switch (cmd) {
            case "sites":
                cmdSites();
                break;
            case "home":
                cmdHome(a1);
                break;
            case "cat":
                cmdCategory(a1, a2);
                break;
            case "detail":
                cmdDetail(a1, a2, dumpOnly);
                break;
            case "search":
                cmdSearch(a1);
                break;
            case "play":
                cmdPlay(a1, a2, positional.size() > 3 ? positional.get(3) : "", !noPlay, dumpOnly);
                break;
            case "verify":
                cmdVerify();
                break;
            default:
                usage();
        }
    }

    private ConfigLoader.Config loadConfig(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            throw new IllegalArgumentException("找不到配置文件: " + f.getAbsolutePath()
                    + "\n提示：从 app/assets/config/demo-safe.json 复制一份到 " + DEFAULT_CONFIG);
        }
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return ConfigLoader.parse(text);
    }

    private void printConfigSummary() {
        System.out.println("配置已加载：启用 " + cfg.sites.size() + " 个内容源");
        if (!cfg.rejected.isEmpty()) {
            System.out.println("被安全策略拒绝 " + cfg.rejected.size() + " 个：");
            for (String r : cfg.rejected) {
                System.out.println("   - " + r);
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------ 各命令实现

    private void cmdSites() {
        int i = 1;
        for (SiteConfig s : cfg.sites) {
            System.out.printf("%2d) %-28s key=%-20s type=%-6s enabled=%s%n",
                    i++, s.name, s.key, s.type, s.enabled);
        }
    }

    private void cmdHome(String key) {
        SiteConfig site = pick(key);
        if (site == null) {
            return;
        }
        Items.HomeResult home = spider(site).homeContent();
        System.out.println("分类 " + home.classes.size() + " 个：");
        for (Items.ClassItem c : home.classes) {
            System.out.println("   " + c.typeId + "  " + c.typeName);
        }
        showList(home.list);
    }

    private void cmdCategory(String key, String tid) {
        SiteConfig site = pick(key);
        if (site == null) {
            return;
        }
        showList(spider(site).categoryContent(tid == null ? "" : tid, 1, null));
    }

    private void cmdDetail(String key, String id, boolean dump) {
        SiteConfig site = pick(key);
        if (site == null || id == null) {
            System.out.println("用法: detail <站点key> <影片id>");
            return;
        }
        Items.VodDetail d = spider(site).detailContent(id);
        lastDetail = d;
        lastSiteKey = site.key;
        System.out.println("标题: " + d.vodName);
        if (!d.typeName.isEmpty()) {
            System.out.println("分类: " + d.typeName);
        }
        if (!d.vodRemarks.isEmpty()) {
            System.out.println("备注: " + d.vodRemarks);
        }
        if (!d.vodContent.isEmpty()) {
            System.out.println("简介: " + d.vodContent);
        }
        System.out.println("播放源 " + d.playFrom.size() + " 个：");
        for (int i = 0; i < d.playFrom.size(); i++) {
            String urls = i < d.playUrl.size() ? d.playUrl.get(i) : "";
            int cnt = urls.isEmpty() ? 0 : urls.split("#").length;
            System.out.printf("   %d) %s （%d 集/项）%n", i + 1, d.playFrom.get(i), cnt);
        }
        if (dump && !d.playUrl.isEmpty()) {
            System.out.println("\n原始 playUrl[0]:");
            System.out.println("   " + d.playUrl.get(0));
        }
    }

    private void cmdSearch(String keyword) {
        if (keyword == null) {
            System.out.println("用法: search <关键词>");
            return;
        }
        List<Items.VideoItem> all = new ArrayList<Items.VideoItem>();
        for (SiteConfig s : cfg.enabledSites()) {
            if (!s.searchable) {
                continue;
            }
            try {
                all.addAll(spider(s).searchContent(keyword, false));
            } catch (RuntimeException e) {
                System.out.println("   (" + s.name + " 搜索失败: " + e.getMessage() + ")");
            }
        }
        showList(all);
    }

    private void cmdPlay(String key, String playId, String flag, boolean doPlay, boolean dump) {
        SiteConfig site = pick(key);
        if (site == null || playId == null) {
            System.out.println("用法: play <站点key> <播放id> [播放源名]");
            return;
        }
        Items.PlayResult pr = spider(site).playerContent(flag == null ? "" : flag, playId);
        if (!pr.ok()) {
            System.out.println("解析失败: " + pr.message);
            return;
        }
        lastPlayUrl = pr.url;
        System.out.println("播放地址: " + pr.url);
        System.out.println("格式: " + pr.format);
        if (!pr.headers.isEmpty()) {
            System.out.println("请求头: " + pr.headers);
        }
        if (dump || !doPlay) {
            return;
        }
        launchPlayer(pr);
    }

    private void cmdVerify() {
        SecurityGuard guard = http.guard();
        int problems = 0;
        System.out.println("== 站点 ==");
        for (SiteConfig s : cfg.sites) {
            List<String> p = s.validate();
            System.out.printf("  [%s] %s | %s | type=%s%n",
                    p.isEmpty() ? "正常" : "问题", s.key, s.name, s.type);
            if (!p.isEmpty()) {
                problems++;
                System.out.println("        " + p);
            }
            if (!s.api.isEmpty()) {
                String why = guard.reason(s.api);
                if (why != null) {
                    problems++;
                    System.out.println("        api 被守卫拒绝: " + why);
                }
            }
        }
        if (!cfg.rejected.isEmpty()) {
            System.out.println("== 被拒绝 ==");
            for (String r : cfg.rejected) {
                problems++;
                System.out.println("   " + r);
            }
        }
        System.out.println(problems == 0 ? "\n未发现问题" : "\n发现 " + problems + " 个问题");
    }

    // ---------------------------------------------------------------- 交互模式

    private void repl() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("进入交互模式。输入 help 看命令，exit 退出。");
        cmdSites();
        while (true) {
            System.out.print("\n> ");
            String line = in.readLine();
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\s+");
            String c = p[0].toLowerCase(Locale.ROOT);
            try {
                if ("exit".equals(c) || "quit".equals(c) || "q".equals(c)) {
                    break;
                } else if ("help".equals(c) || "?".equals(c)) {
                    help();
                } else if ("sites".equals(c)) {
                    cmdSites();
                } else if ("home".equals(c)) {
                    cmdHome(p.length > 1 ? resolveSiteArg(p[1]) : null);
                } else if ("cat".equals(c)) {
                    cmdCategory(p.length > 1 ? resolveSiteArg(p[1]) : null, p.length > 2 ? p[2] : "");
                } else if ("detail".equals(c)) {
                    if (p.length > 2) {
                        cmdDetail(resolveSiteArg(p[1]), p[2], false);
                    } else {
                        // 只有一个参数：当作列表里的序号
                        cmdDetail(lastSiteKey, resolveListArg(p[1]), false);
                    }
                } else if ("search".equals(c)) {
                    cmdSearch(p.length > 1 ? joinTail(p, 1) : null);
                } else if ("play".equals(c)) {
                    handlePlayInteractive(p);
                } else if ("open".equals(c)) {
                    if (lastPlayUrl.isEmpty()) {
                        System.out.println("还没有解析出播放地址，先执行 play");
                    } else {
                        launchPlayer(lastPlayUrl, "上次解析的地址", null);
                    }
                } else if ("save".equals(c)) {
                    savePlaylist();
                } else if ("verify".equals(c)) {
                    cmdVerify();
                } else {
                    System.out.println("未知命令，输入 help 查看");
                }
            } catch (RuntimeException e) {
                System.out.println("出错: " + e.getMessage());
            }
        }
    }

    private void handlePlayInteractive(String[] p) {
        // play 站点 key/id 播放id [源序号]
        if (p.length >= 3) {
            String site = resolveSiteArg(p[1]);
            String id = p[2];
            String flag = p.length > 3 ? p[3] : "";
            cmdPlay(site, id, flag, true, false);
            return;
        }
        if (p.length == 2 && lastDetail != null) {
            // play <集序号>：用上一个详情
            int idx = Integer.parseInt(p[1]) - 1;
            List<String[]> eps = splitEpisodes(lastDetail.playUrl.isEmpty() ? "" : lastDetail.playUrl.get(0));
            if (idx < 0 || idx >= eps.size()) {
                System.out.println("序号超出范围，共 " + eps.size() + " 集");
                return;
            }
            String flag = lastDetail.playFrom.isEmpty() ? "" : lastDetail.playFrom.get(0);
            cmdPlay(lastSiteKey, eps.get(idx)[1], flag, true, false);
            return;
        }
        System.out.println("用法: play <站点key|序号> <播放id> [源名]   或先 detail 再 play <集序号>");
    }

    private void savePlaylist() {
        if (lastDetail == null) {
            System.out.println("先执行一次 detail");
            return;
        }
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < lastDetail.playFrom.size(); i++) {
            String flag = lastDetail.playFrom.get(i);
            String block = i < lastDetail.playUrl.size() ? lastDetail.playUrl.get(i) : "";
            for (String[] ep : splitEpisodes(block)) {
                sb.append("#EXTINF:-1 group-title=\"").append(flag).append("\",")
                        .append(lastDetail.vodName).append(" - ").append(ep[0]).append('\n');
                sb.append(ep[1]).append('\n');
            }
        }
        try {
            File out = new File("playlist.m3u");
            Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("已写出 " + out.getAbsolutePath() + "（可用 VLC / PotPlayer 打开）");
        } catch (Exception e) {
            System.out.println("写文件失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 播放

    private void launchPlayer(Items.PlayResult pr) {
        launchPlayer(pr.url, "SafeTVBox", pr.headers);
    }

    private void launchPlayer(String url, String title, java.util.Map<String, String> headers) {
        List<String> cmd = buildPlayerCommand(url, title, headers);
        if (cmd == null) {
            System.out.println("没找到可用播放器。可以：");
            System.out.println("  1) 装 ffmpeg（自带 ffplay）或 VLC / mpv，重新执行 play");
            System.out.println("  2) 手动播放:  ffplay \"" + url + "\"");
            System.out.println("  3) 执行 save 生成 playlist.m3u，用本地播放器打开");
            return;
        }
        try {
            System.out.println("启动播放器: " + cmd.get(0) + " …");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process proc = pb.start();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        proc.waitFor();
                    } catch (InterruptedException ignored) {
                        // ignore
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (java.io.IOException e) {
            System.out.println("启动播放器失败: " + e.getMessage());
            System.out.println("可以手动播放: ffplay \"" + url + "\"");
        }
    }

    /**
     * 按顺序探测 ffplay / mpv / vlc，找到哪个用哪个。
     * 只启动本机上已安装的播放器，不会下载任何东西。
     */
    private List<String> buildPlayerCommand(String url, String title, java.util.Map<String, String> headers) {
        String headerArg = null;
        if (headers != null && !headers.isEmpty()) {
            StringBuilder h = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            headerArg = h.toString();
        }

        String ffplay = findExecutable("ffplay");
        if (ffplay != null) {
            List<String> c = new ArrayList<String>();
            c.add(ffplay);
            c.add("-window_title");
            c.add(title);
            c.add("-autoexit");
            c.add("-loglevel");
            c.add("warning");
            if (headerArg != null) {
                c.add("-headers");
                c.add(headerArg);
            }
            c.add(url);
            return c;
        }

        String mpv = findExecutable("mpv");
        if (mpv != null) {
            List<String> c = new ArrayList<String>();
            c.add(mpv);
            c.add("--title=" + title);
            if (headerArg != null) {
                c.add("--http-header-fields=" + headerArg.replace("\r\n", ","));
            }
            c.add(url);
            return c;
        }

        String vlc = findExecutable("vlc");
        if (vlc == null) {
            // Windows 上 VLC 通常不在 PATH 里，试一下默认安装路径
            for (String p : new String[]{
                    "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                    "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe"}) {
                if (new File(p).exists()) {
                    vlc = p;
                    break;
                }
            }
        }
        if (vlc != null) {
            List<String> c = new ArrayList<String>();
            c.add(vlc);
            if (headerArg != null && headerArg.contains("Referer")) {
                String ref = headers.get("Referer");
                c.add("--http-referrer=" + ref);
            }
            if (headerArg != null && headerArg.contains("User-Agent")) {
                c.add("--http-user-agent=" + headers.get("User-Agent"));
            }
            c.add(url);
            return c;
        }
        return null;
    }

    /** 在 PATH 里找可执行文件，Windows 上会带上 .exe 后缀试一遍。 */
    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String sep = win ? ";" : ":";
        for (String dir : path.split(sep)) {
            if (dir.isEmpty()) {
                continue;
            }
            File f = new File(dir, name + (win ? ".exe" : ""));
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
            File plain = new File(dir, name);
            if (plain.isFile()) {
                return plain.getAbsolutePath();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private SiteConfig pick(String keyOrNull) {
        if (cfg.sites.isEmpty()) {
            System.out.println("配置里没有内容源");
            return null;
        }
        if (keyOrNull == null || keyOrNull.trim().isEmpty()) {
            return cfg.enabledSites().isEmpty() ? cfg.sites.get(0) : cfg.enabledSites().get(0);
        }
        String k = keyOrNull.trim();
        for (SiteConfig s : cfg.sites) {
            if (s.key.equals(k) || s.name.equals(k)) {
                return s;
            }
        }
        // 允许用序号
        try {
            int idx = Integer.parseInt(k) - 1;
            if (idx >= 0 && idx < cfg.sites.size()) {
                return cfg.sites.get(idx);
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        System.out.println("找不到内容源: " + k);
        return null;
    }

    /** 交互模式下把可能是序号的参数解析成站点 key */
    private String resolveSiteArg(String arg) {
        SiteConfig s = pick(arg);
        return s == null ? arg : s.key;
    }

    private String resolveListArg(String arg) {
        try {
            int idx = Integer.parseInt(arg) - 1;
            if (idx >= 0 && idx < lastList.size()) {
                return lastList.get(idx).vodId;
            }
        } catch (NumberFormatException ignored) {
            // 不是序号就当 id 用
        }
        return arg;
    }

    private DeclSpider spider(SiteConfig site) {
        return new DeclSpider(site, http);
    }

    private void showList(List<Items.VideoItem> list) {
        lastList = list == null ? new ArrayList<Items.VideoItem>() : list;
        System.out.println("共 " + lastList.size() + " 条：");
        int i = 1;
        for (Items.VideoItem v : lastList) {
            System.out.printf("%3d) %-32s id=%-40s %s%n",
                    i++, cut(v.vodName, 32), cut(v.vodId, 40),
                    v.vodRemarks == null ? "" : v.vodRemarks);
        }
    }

    static List<String[]> splitEpisodes(String block) {
        List<String[]> out = new ArrayList<String[]>();
        if (block == null || block.isEmpty()) {
            return out;
        }
        String[] parts = block.split("#");
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            int at = p.indexOf('$');
            if (at < 0) {
                out.add(new String[]{"播放", p});
            } else {
                out.add(new String[]{p.substring(0, at), p.substring(at + 1)});
            }
        }
        return out;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String joinTail(String[] arr, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < arr.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static void help() {
        System.out.println("可用命令：");
        System.out.println("  sites                     列出内容源");
        System.out.println("  home [站点key|序号]        首页分类与列表");
        System.out.println("  cat  [站点key] [分类id]    分类内容");
        System.out.println("  detail <站点key> <影片id>  详情与播放源");
        System.out.println("  detail <列表序号>          直接用上一次列表里的序号查看详情");
        System.out.println("  search <关键词>            跨源搜索");
        System.out.println("  play <站点key> <播放id> [源名]   解析并播放");
        System.out.println("  play <集序号>              用上一个详情直接播放第 N 集");
        System.out.println("  open                      用 ffplay 重播上次解析出的地址");
        System.out.println("  save                      把当前详情导出成 playlist.m3u");
        System.out.println("  verify                    体检当前配置");
        System.out.println("  exit                      退出");
    }

    private static void usage() {
        System.out.println("用法: java -cp out com.tvbox.core.cli.Desktop [选项] [命令] [参数]");
        System.out.println("选项: -c <配置路径>   默认 config/demo-safe.json");
        System.out.println("      --allow-private  放行内网地址（测试自己的局域网源时用）");
        System.out.println("      --dump           只打印解析结果，不启动播放器");
        System.out.println("      --no-play        解析地址但不播放");
        System.out.println("命令: sites / home / cat / detail / search / play / verify；不带命令则进入交互模式");
    }

    static {
        // 引用一下 Json，避免 IDE 报未使用（同时确认 core 编译链路正常）
        Json.str("");
    }
}
