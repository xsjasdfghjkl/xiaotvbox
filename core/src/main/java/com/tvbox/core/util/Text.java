package com.tvbox.core.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 文本 / 编码工具，全部基于 JDK 标准库实现，不引入第三方依赖。 */
public final class Text {

    private Text() {
    }

    // ------------------------------------------------------------------ 编码

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest((s == null ? "" : s).getBytes("UTF-8"));
            return hex(d);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes("UTF-8"));
            return hex(d);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 自实现 Base64，避免 java.util.Base64 在低版本 Android（API<26）不可用。 */
    public static String base64Encode(String s) {
        try {
            return base64EncodeBytes((s == null ? "" : s).getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String base64EncodeBytes(byte[] data) {
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int rem = Math.min(3, data.length - i);
            int n = 0;
            for (int k = 0; k < 3; k++) {
                n = (n << 8) | (k < rem ? (data[i + k] & 0xff) : 0);
            }
            sb.append(B64[(n >> 18) & 63]);
            sb.append(B64[(n >> 12) & 63]);
            sb.append(rem > 1 ? B64[(n >> 6) & 63] : '=');
            sb.append(rem > 2 ? B64[n & 63] : '=');
        }
        return sb.toString();
    }

    public static String base64Decode(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') {
                continue;
            }
            clean.append(c);
        }
        int len = clean.length();
        StringBuilder sb = new StringBuilder(len * 3 / 4);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            int v = b64val(clean.charAt(i));
            if (v < 0) {
                return "";
            }
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                sb.append((char) ((buf >> bits) & 0xff));
            }
        }
        return sb.toString();
    }

    private static int b64val(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 正则

    /** 正则提取（带输入长度上限，降低 ReDoS 影响面）。 */
    public static List<String> findAll(String text, String pattern, int group) {
        List<String> out = new ArrayList<String>();
        if (text == null || pattern == null || text.length() > 4 * 1024 * 1024) {
            return out;
        }
        try {
            Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
            Matcher m = p.matcher(text);
            while (m.find() && out.size() < 5000) {
                out.add(m.group(group <= m.groupCount() ? group : 0));
            }
        } catch (RuntimeException ignored) {
            return out;
        }
        return out;
    }

    public static String findFirst(String text, String pattern, int group) {
        List<String> r = findAll(text, pattern, group);
        return r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------------ 杂项

    public static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]*>", "").trim();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public static String defaultIfEmpty(String s, String def) {
        return isEmpty(s) ? def : s;
    }
}
