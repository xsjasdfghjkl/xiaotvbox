package com.tvbox.core;

import com.tvbox.core.json.Json;
import com.tvbox.core.model.Items;
import com.tvbox.core.model.SiteConfig;
import com.tvbox.core.net.SecurityGuard;
import com.tvbox.core.parse.ConfigLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置体检工具：不联网，只做静态校验。
 *
 * 用法：java -cp <out> com.tvbox.core.VerifyConfig <配置文件路径>
 *
 * 会报告：
 *  1. 每个站点是否被准入；
 *  2. 配置里出现的每个 URL 是否会被安全守卫放行（内网/非 http(s) 会被点名）；
 *  3. 是否夹带了 jar / dex / js 等远程代码字段。
 */
public class VerifyConfig {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: VerifyConfig <config.json>");
            return;
        }
        String text = new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8);
        SecurityGuard guard = SecurityGuard.strictDefault();
        int problems = 0;
        try {
            ConfigLoader.Config cfg = ConfigLoader.parse(text);
            System.out.println("== 站点 ==");
            for (SiteConfig s : cfg.sites) {
                System.out.println("  [准入] " + s.key + " | " + s.name + " | type=" + s.type
                        + " | enabled=" + s.enabled + " | api=" + (s.api.isEmpty() ? "-" : s.api));
                List<String> p = s.validate();
                if (!p.isEmpty()) {
                    problems++;
                    System.out.println("     问题: " + p);
                }
            }
            System.out.println("== 被拒绝 ==");
            if (cfg.rejected.isEmpty()) {
                System.out.println("  无");
            } else {
                for (String r : cfg.rejected) {
                    problems++;
                    System.out.println("  [拒绝] " + r);
                }
            }
            System.out.println("== URL 检查 ==");
            List<String> urls = collectUrls(text);
            for (String u : urls) {
                String why = guard.reason(u);
                if (why == null) {
                    System.out.println("  [放行] " + u);
                } else {
                    problems++;
                    System.out.println("  [拦截] " + u + " -> " + why);
                }
            }
            System.out.println();
            System.out.println("结论: " + (problems == 0 ? "未发现问题" : "发现 " + problems + " 个需要关注的问题"));
        } catch (RuntimeException e) {
            System.out.println("配置解析失败: " + e.getMessage());
            System.exit(2);
        }
    }

    /** 粗略收集配置里所有 http(s)/其它协议的 URL 文本。 */
    static List<String> collectUrls(String text) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int st = text.indexOf("http", i);
            if (st < 0) {
                break;
            }
            int end = st;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '"' || c == '\'' || c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n') {
                    break;
                }
                end++;
            }
            String u = text.substring(st, end);
            i = end;
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        // 也扫一下明显的非 http 协议
        for (String proto : new String[]{"file://", "jar:", "ftp://", "content://"}) {
            if (text.contains(proto)) {
                out.add(proto + "<检测到该协议>");
            }
        }
        return out;
    }
}
