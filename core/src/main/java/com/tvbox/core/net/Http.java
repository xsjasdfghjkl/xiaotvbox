package com.tvbox.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 极简 HTTP 客户端，基于 JDK HttpURLConnection，无第三方依赖。
 *
 * 所有请求都必须携带 SecurityGuard：
 *  - 请求前校验 URL；
 *  - 关闭自动重定向，手动逐跳校验后再跟随，最多 3 跳；
 *  - 响应体有硬上限（默认 8MB），读取超过即中断；
 *  - 连接 / 读取均有超时。
 */
public final class Http {

    public static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_CONNECT_MS = 8000;
    public static final int DEFAULT_READ_MS = 15000;
    public static final int MAX_REDIRECTS = 3;

    private final SecurityGuard guard;
    private int maxBytes = DEFAULT_MAX_BYTES;
    private int connectMs = DEFAULT_CONNECT_MS;
    private int readMs = DEFAULT_READ_MS;
    private String userAgent = "SafeTVBox/1.0 (self-hosted, non-commercial)";

    public Http(SecurityGuard guard) {
        if (guard == null) {
            throw new IllegalArgumentException("SecurityGuard 不能为空：出网请求必须被约束");
        }
        this.guard = guard;
    }

    /** 暴露守卫，便于上层（如播放器地址校验）复用同一套策略。 */
    public SecurityGuard guard() {
        return guard;
    }

    public Http maxBytes(int v) {
        this.maxBytes = v;
        return this;
    }

    public Http timeout(int connectMs, int readMs) {
        this.connectMs = connectMs;
        this.readMs = readMs;
        return this;
    }

    public Http userAgent(String ua) {
        if (ua != null && !ua.isEmpty()) {
            this.userAgent = ua;
        }
        return this;
    }

    // ------------------------------------------------------------------ API

    public Response get(String url) {
        return get(url, null);
    }

    public Response get(String url, Map<String, String> headers) {
        return request("GET", url, headers, null);
    }

    public Response post(String url, Map<String, String> headers, String body) {
        return request("POST", url, headers, body);
    }

    public Response request(String method, String url, Map<String, String> headers, String body) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            guard.check(current);
            Response r = once(method, current, headers, body);
            String loc = r.header("Location");
            if (loc != null && (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308)) {
                String next = resolve(current, loc);
                String why = guard.reason(next);
                if (why != null) {
                    throw new SecurityException("重定向目标被拒绝: " + why);
                }
                if (r.code == 303) {
                    method = "GET";
                    body = null;
                }
                current = next;
                continue;
            }
            r.finalUrl = current;
            return r;
        }
        throw new IOException2("重定向次数过多");
    }

    private Response once(String method, String url, Map<String, String> headers, String body) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept-Encoding", "gzip, deflate");
            c.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    // 禁止配置覆盖 Host / Content-Length 等 hop-by-hop 敏感头
                    String k = e.getKey();
                    if (k == null) {
                        continue;
                    }
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("host") || lk.equals("content-length") || lk.equals("connection")) {
                        continue;
                    }
                    c.setRequestProperty(k, e.getValue());
                }
            }
            if (body != null && !body.isEmpty()) {
                c.setDoOutput(true);
                if (c.getRequestProperty("Content-Type") == null) {
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                }
                byte[] payload = body.getBytes("UTF-8");
                OutputStream os = c.getOutputStream();
                try {
                    os.write(payload);
                    os.flush();
                } finally {
                    close(os);
                }
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            byte[] data = readCapped(in);
            Map<String, String> respHeaders = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                respHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
            return new Response(code, data, charsetOf(c), respHeaders);
        } catch (IOException e) {
            throw new IOException2("网络请求失败: " + SecurityGuard.safeLog(url) + " -> " + e.getMessage());
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException2("网络请求异常: " + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private byte[] readCapped(InputStream raw) throws IOException {
        if (raw == null) {
            return new byte[0];
        }
        InputStream in = raw;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("响应体超过上限 " + maxBytes + " 字节");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            close(in);
        }
    }

    private static String charsetOf(HttpURLConnection c) {
        String ct = c.getHeaderField("Content-Type");
        if (ct != null) {
            int i = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String cs = ct.substring(i + 8).trim();
                int semi = cs.indexOf(';');
                if (semi > 0) {
                    cs = cs.substring(0, semi);
                }
                cs = cs.replace("\"", "").trim();
                if (!cs.isEmpty()) {
                    return cs;
                }
            }
        }
        return "UTF-8";
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 相对 Location 转绝对 URL */
    static String resolve(String base, String loc) {
        try {
            java.net.URI b = java.net.URI.create(base);
            java.net.URI r = b.resolve(java.net.URI.create(loc));
            return r.toString();
        } catch (IllegalArgumentException e) {
            return loc;
        }
    }

    // ------------------------------------------------------------------ 结果

    public static final class Response {
        public final int code;
        private final byte[] data;
        private final String charset;
        private final Map<String, String> headers;
        public String finalUrl;

        Response(int code, byte[] data, String charset, Map<String, String> headers) {
            this.code = code;
            this.data = data;
            this.charset = charset;
            this.headers = headers;
        }

        public byte[] bytes() {
            return data;
        }

        public String body() {
            try {
                return new String(decode(), charset);
            } catch (Exception e) {
                return new String(data, java.nio.charset.Charset.forName("UTF-8"));
            }
        }

        private byte[] decode() {
            String enc = header("Content-Encoding");
            if (enc == null) {
                return data;
            }
            String e = enc.toLowerCase(Locale.ROOT);
            try {
                if (e.contains("gzip")) {
                    return readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(data)));
                }
                if (e.contains("deflate")) {
                    return readAll(new InflaterInputStream(new java.io.ByteArrayInputStream(data)));
                }
            } catch (IOException ignored) {
                return data;
            }
            return data;
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            close(in);
            return bos.toByteArray();
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public Map<String, String> headers() {
            return Collections.unmodifiableMap(headers);
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** 统一包装，避免到处抛受检异常。 */
    public static class IOException2 extends RuntimeException {
        public IOException2(String m) {
            super(m);
        }
    }

    public static Map<String, String> form(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        List<String> keys = new ArrayList<String>(kv.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(netEncode(keys.get(i))).append('=').append(netEncode(kv.get(keys.get(i))));
        }
        Map<String, String> dummy = new LinkedHashMap<String, String>();
        return dummy;
    }

    public static String encodeForm(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(netEncode(e.getKey())).append('=').append(netEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String netEncode(String s) {
        return com.tvbox.core.util.Text.urlEncode(s);
    }
}
