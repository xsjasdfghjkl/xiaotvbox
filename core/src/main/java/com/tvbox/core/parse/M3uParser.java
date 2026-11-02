package com.tvbox.core.parse;

import com.tvbox.core.model.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3U / M3U8 文本解析器。
 *
 * 只做纯文本处理：不下载分片、不递归、不执行任何指令。
 * 用于两类稳定内容源：
 *  1) IPTV 公开索引（.m3u 列表）→ 转成频道卡片；
 *  2) 播放地址是 m3u8 时，本地解析出分片列表，便于自测连通性。
 */
public final class M3uParser {

    private M3uParser() {
    }

    /** 解析 #EXTINF 播放列表，返回 {name, url, logo, group} 列表。 */
    public static List<Map<String, String>> playList(String text, int limit) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        String[] lines = text.split("\\r?\\n");
        String pendingName = null;
        String pendingLogo = null;
        String pendingGroup = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                int comma = line.indexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "";
                pendingLogo = attr(line, "tvg-logo");
                pendingGroup = attr(line, "group-title");
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("name", pendingName == null ? line : pendingName);
            m.put("url", line);
            m.put("logo", pendingLogo == null ? "" : pendingLogo);
            m.put("group", pendingGroup == null ? "" : pendingGroup);
            out.add(m);
            pendingName = null;
            pendingLogo = null;
            pendingGroup = null;
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public static List<Items.VideoItem> toItems(String text, int limit, String siteKey) {
        List<Items.VideoItem> out = new ArrayList<Items.VideoItem>();
        for (Map<String, String> m : playList(text, limit)) {
            Items.VideoItem v = new Items.VideoItem();
            v.siteKey = siteKey;
            v.vodId = m.get("url");
            v.vodName = m.get("name");
            v.vodPic = m.get("logo");
            v.typeName = m.get("group");
            out.add(v);
        }
        return out;
    }

    /** 从 #EXTINF 行里取属性，如 tvg-logo="http://..." */
    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) {
            return null;
        }
        int st = i + key.length() + 1;
        if (st >= line.length()) {
            return null;
        }
        char q = line.charAt(st);
        if (q == '"' || q == '\'') {
            int end = line.indexOf(q, st + 1);
            return end < 0 ? line.substring(st + 1) : line.substring(st + 1, end);
        }
        int end = line.indexOf(',', st);
        return end < 0 ? line.substring(st) : line.substring(st, end);
    }

    /** 判断 m3u8 是否为"主播放列表"（含多个码率子列表）。 */
    public static boolean isMasterPlaylist(String text) {
        return text != null && text.contains("#EXT-X-STREAM-INF");
    }

    /** 取主播放列表里的分片列表地址（相对地址需自行 resolve）。 */
    public static List<String> mediaPlaylists(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.length) {
                String nxt = lines[i + 1].trim();
                if (!nxt.isEmpty() && !nxt.startsWith("#")) {
                    out.add(nxt);
                }
            }
        }
        return out;
    }

    /** 解析媒体播放列表里的分片地址（相对地址需自行 resolve）。 */
    public static List<String> segments(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** 把相对地址拼成绝对地址。 */
    public static String resolve(String base, String maybeRelative) {
        if (maybeRelative == null || maybeRelative.isEmpty()) {
            return "";
        }
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative;
        }
        int slash = base.lastIndexOf('/');
        String dir = slash >= 0 ? base.substring(0, slash + 1) : base + "/";
        if (maybeRelative.startsWith("/")) {
            int proto = base.indexOf("://");
            if (proto > 0) {
                int root = base.indexOf('/', proto + 3);
                return (root > 0 ? base.substring(0, root) : base) + maybeRelative;
            }
        }
        return dir + maybeRelative;
    }
}
