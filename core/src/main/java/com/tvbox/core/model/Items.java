package com.tvbox.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据模型集合。字段命名沿用 TVBox / CatVod 的习惯，方便对照原版学习。 */
public final class Items {

    private Items() {
    }

    /** 分类（首页横向 tab） */
    public static class ClassItem {
        public String typeId;
        public String typeName;

        public ClassItem() {
        }

        public ClassItem(String id, String name) {
            this.typeId = id;
            this.typeName = name;
        }
    }

    /** 列表卡片 */
    public static class VideoItem {
        public String vodId = "";
        public String vodName = "";
        public String vodPic = "";
        public String vodRemarks = "";   // 右上角角标，如 "HD"、"更新至12集"
        public String vodYear = "";
        public String vodArea = "";
        public String vodActor = "";
        public String vodDirector = "";
        public String vodContent = "";
        public String typeName = "";
        public String siteKey = "";      // 所属站点，跨站搜索时用
        public String siteName = "";

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("vod_id", vodId);
            m.put("vod_name", vodName);
            m.put("vod_pic", vodPic);
            m.put("vod_remarks", vodRemarks);
            m.put("vod_year", vodYear);
            m.put("vod_area", vodArea);
            m.put("vod_actor", vodActor);
            m.put("vod_director", vodDirector);
            m.put("vod_content", vodContent);
            m.put("type_name", typeName);
            m.put("site_key", siteKey);
            m.put("site_name", siteName);
            return m;
        }
    }

    /** 详情页 */
    public static class VodDetail {
        public String vodId = "";
        public String vodName = "";
        public String vodPic = "";
        public String vodActor = "";
        public String vodDirector = "";
        public String vodContent = "";
        public String vodRemarks = "";
        public String vodYear = "";
        public String vodArea = "";
        public String typeName = "";
        public String siteKey = "";

        /** 播放源名列表，如 ["m3u8", "yun"] */
        public List<String> playFrom = new ArrayList<String>();
        /** 与 playFrom 一一对应，每项内部用 "#" 分隔多集，单集形如 "第1集$url" */
        public List<String> playUrl = new ArrayList<String>();

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("vod_id", vodId);
            m.put("vod_name", vodName);
            m.put("vod_pic", vodPic);
            m.put("vod_actor", vodActor);
            m.put("vod_director", vodDirector);
            m.put("vod_content", vodContent);
            m.put("vod_remarks", vodRemarks);
            m.put("vod_year", vodYear);
            m.put("vod_area", vodArea);
            m.put("type_name", typeName);
            m.put("vod_play_from", join(playFrom, "$$$"));
            m.put("vod_play_url", join(playUrl, "$$$"));
            return m;
        }

        private static String join(List<String> l, String sep) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(l.get(i));
            }
            return sb.toString();
        }
    }

    /** 播放器需要的最终结果 */
    public static class PlayResult {
        /** 播放地址，可能是 http(s) 直链，也可能是 m3u8 */
        public String url = "";
        /** 播放时需要的请求头（Referer / User-Agent 等） */
        public Map<String, String> headers = new LinkedHashMap<String, String>();
        /** 内容类型提示：m3u8 / mp4 / auto */
        public String format = "auto";
        /** 出错时的提示文案 */
        public String message = "";
        /** 是否需要外部二次解析（本项目不支持，恒为 0） */
        public int parse = 0;

        public boolean ok() {
            return url != null && (url.startsWith("http://") || url.startsWith("https://"));
        }
    }

    /** 首页聚合结果 */
    public static class HomeResult {
        public List<ClassItem> classes = new ArrayList<ClassItem>();
        public List<VideoItem> list = new ArrayList<VideoItem>();
        public Map<String, Object> filters = new LinkedHashMap<String, Object>();
    }
}
