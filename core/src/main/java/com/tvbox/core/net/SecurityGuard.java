package com.tvbox.core.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网络访问守卫：所有出网请求必须先过这一层。
 *
 * 防护点：
 *  1. 协议白名单——只允许 http / https，拒绝 file://  jar://  ftp://  gopher://  content:// 等。
 *  2. 目标地址黑名单——拒绝本机、内网、链路本地、运营商级 NAT、IPv6 私有地址段，
 *     以及常见云元数据地址（169.254.169.254）。这是 SSRF 的主要防线。
 *  3. 端口白名单——只允许常见 Web 端口，避免被当作端口扫描跳板。
 *  4. 可选域名白名单——配置里一旦声明 allowHosts，则只放行列出的域名。
 *  5. 拒绝 URL 中内嵌用户名密码（防止凭据混淆与 URL 解析差异攻击）。
 *  6. 重定向逐跳校验——不允许"先跳外网再跳内网"。
 *
 * 已知局限（诚实地写在这里）：
 *  校验发生在 DNS 解析之后、连接建立之前，理论上存在 DNS rebinding 的 TOCTOU 窗口。
 *  彻底解决需要自定义 Socket 层绑定解析结果，本项目未实现；
 *  对于自用 TV 客户端，威胁模型主要是"恶意配置诱导访问内网"，本层已足够。
 */
public final class SecurityGuard {

    public static final Set<Integer> DEFAULT_ALLOWED_PORTS = Collections.unmodifiableSet(
            new LinkedHashSet<Integer>(Arrays.asList(80, 443, 8080, 8443)));

    private final Set<Integer> allowedPorts;
    private final Set<String> allowedHosts;   // 为空表示不限制
    private final Set<String> blockedHosts;
    private final boolean allowPrivateForTest;

    private SecurityGuard(Builder b) {
        this.allowedPorts = new LinkedHashSet<Integer>(b.allowedPorts);
        this.allowedHosts = new LinkedHashSet<String>(b.allowedHosts);
        this.blockedHosts = new LinkedHashSet<String>(b.blockedHosts);
        this.allowPrivateForTest = b.allowPrivateForTest;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SecurityGuard strictDefault() {
        return builder().build();
    }

    public static final class Builder {
        final Set<Integer> allowedPorts = new LinkedHashSet<Integer>(DEFAULT_ALLOWED_PORTS);
        final Set<String> allowedHosts = new LinkedHashSet<String>();
        final Set<String> blockedHosts = new LinkedHashSet<String>();
        boolean allowPrivateForTest = false;

        public Builder port(int p) {
            allowedPorts.add(p);
            return this;
        }

        public Builder allowHost(String host) {
            if (host != null && !host.trim().isEmpty()) {
                allowedHosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        public Builder blockHost(String host) {
            if (host != null && !host.trim().isEmpty()) {
                blockedHosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /** 仅单元测试使用：允许回环地址。 */
        public Builder allowPrivateForTest(boolean v) {
            this.allowPrivateForTest = v;
            return this;
        }

        public SecurityGuard build() {
            return new SecurityGuard(this);
        }
    }

    // ------------------------------------------------------------------ 校验

    public void check(String url) {
        String why = reason(url);
        if (why != null) {
            throw new SecurityException("请求被安全策略拒绝: " + why + " (url=" + safeLog(url) + ")");
        }
    }

    /** 返回 null 表示允许；否则返回拒绝原因。 */
    public String reason(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "空 URL";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "URL 格式非法";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "协议不允许: " + scheme;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "缺少主机名";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null) {
            return "URL 中不允许内嵌用户名密码";
        }
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        if (!allowedPorts.contains(port)) {
            return "端口不允许: " + port;
        }
        if (blockedHosts.contains(host)) {
            return "主机在黑名单中";
        }
        if (!allowedHosts.isEmpty()) {
            // 显式白名单命中即放行，不再做解析检查：
            // 用户既然手工把这个域名加进白名单，就等于声明信任它。
            if (!hostAllowed(host)) {
                return "主机不在白名单中: " + host;
            }
            return null;
        }
        if (!allowPrivateForTest) {
            String priv = privateReason(host);
            if (priv != null) {
                return priv;
            }
        }
        return null;
    }

    private boolean hostAllowed(String host) {
        if (allowedHosts.contains(host)) {
            return true;
        }
        for (String h : allowedHosts) {
            if (h.startsWith(".")) {
                if (host.endsWith(h)) {
                    return true;
                }
            } else if (host.endsWith("." + h)) {
                return true;
            }
        }
        return false;
    }

    /** 判断主机名是否解析到/属于私有或保留地址。 */
    private String privateReason(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.equals("metadata.google.internal") || h.equals("metadata")
                || h.endsWith(".internal")) {
            return "禁止访问本机/内部域名";
        }
        // 字面量 IPv4
        if (isLiteralIpv4(h)) {
            if (isPrivateIpv4(h)) {
                return "禁止访问私有/保留 IP: " + h;
            }
            return null;
        }
        // 字面量 IPv6（方括号已由 URI 去掉）
        if (h.indexOf(':') >= 0) {
            if (isPrivateIpv6(h)) {
                return "禁止访问私有 IPv6: " + h;
            }
            return null;
        }
        // 域名：解析后逐个检查
        List<String> addrs;
        try {
            InetAddress[] arr = InetAddress.getAllByName(h);
            addrs = new ArrayList<String>();
            for (InetAddress a : arr) {
                addrs.add(a.getHostAddress());
            }
        } catch (UnknownHostException e) {
            return "域名解析失败: " + h;
        }
        for (String ip : addrs) {
            if (ip.indexOf(':') >= 0) {
                if (isPrivateIpv6(ip)) {
                    return "域名解析到私有 IPv6: " + ip;
                }
            } else if (isPrivateIpv4(ip)) {
                return "域名解析到私有/保留 IP: " + ip;
            }
        }
        return null;
    }

    static boolean isLiteralIpv4(String h) {
        String[] p = h.split("\\.");
        if (p.length != 4) {
            return false;
        }
        for (String s : p) {
            if (s.isEmpty() || s.length() > 3) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(s) > 255) {
                return false;
            }
        }
        return true;
    }

    static boolean isPrivateIpv4(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return true; // 解析不出就按危险处理
        }
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return true;
        }
        if (a == 0 || a == 10 || a == 127) {
            return true;                    // 0.0.0.0/8, 10/8, loopback
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;                    // 172.16/12
        }
        if (a == 192 && b == 168) {
            return true;                    // 192.168/16
        }
        if (a == 169 && b == 254) {
            return true;                    // link-local + 云元数据 169.254.169.254
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return true;                    // CGNAT 100.64/10
        }
        if (a == 192 && b == 0) {
            return true;                    // 192.0.0/24
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return true;                    // benchmark
        }
        if (a >= 224) {
            return true;                    // 组播 / 保留
        }
        return false;
    }

    static boolean isPrivateIpv6(String ip) {
        String s = ip.toLowerCase(Locale.ROOT);
        if (s.startsWith("[")) {
            s = s.substring(1, s.lastIndexOf(']'));
        }
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.equals("::1") || s.equals("::") || s.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (s.startsWith("fe80") || s.startsWith("fec0")) {
            return true;                    // link-local / site-local
        }
        if (s.startsWith("fc") || s.startsWith("fd")) {
            return true;                    // unique local fc00::/7
        }
        if (s.startsWith("ff")) {
            return true;                    // 组播
        }
        // IPv4-mapped ::ffff:10.0.0.1
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0 && isLiteralIpv4(s.substring(lastColon + 1))) {
            return isPrivateIpv4(s.substring(lastColon + 1));
        }
        return false;
    }

    /** 日志脱敏：避免把带 token 的 URL 完整打进日志。 */
    public static String safeLog(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        return base.length() > 120 ? base.substring(0, 120) + "..." : base;
    }

    public Set<Integer> getAllowedPorts() {
        return Collections.unmodifiableSet(allowedPorts);
    }
}
