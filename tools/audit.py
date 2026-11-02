#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SafeTVBox 静态安全审计脚本

用法:
    python3 tools/audit.py                 # 审计整个项目
    python3 tools/audit.py --dir app       # 只审计某个目录

它回答三个问题:
  1. 有没有"能执行任意代码 / 能装载外部代码"的调用？
  2. 有没有绕过安全守卫的网络出口？
  3. 有没有夹带的二进制、可疑域名、超长编码串？

结果分三档:
  BLOCK  必须处理（脚本 exit 1）
  WARN   需要人工确认（会打印位置与内容）
  INFO   参考信息

已知会被列出来但属于"正常"的项，写在 tools/audit-allowlist.txt 里并注明理由。
"""
import os
import re
import sys
import json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------- 检测规则

# 高危：远程代码装载 / 本地命令执行 / 反射
CODE_EXEC = [
    (r"\bDexClassLoader\b", "dex 动态加载"),
    (r"\bPathClassLoader\b", "dex 动态加载"),
    (r"\bURLClassLoader\b", "远程 jar 加载"),
    (r"\bclassLoader\b\s*\.\s*loadClass", "类加载"),
    (r"\bClass\s*\.\s*forName\s*\(", "反射类加载"),
    (r"\bnewInstance\s*\(", "反射实例化"),
    (r"\bgetDeclaredMethod\b|\bgetMethod\s*\(", "反射方法调用"),
    (r"\bsetAccessible\s*\(", "反射越权访问"),
    (r"\bRuntime\s*\.\s*getRuntime\s*\(", "执行本地命令"),
    (r"\bProcessBuilder\b", "执行本地命令"),
    (r"\.exec\s*\(", "执行本地命令"),
    (r"\bSystem\s*\.\s*load\b|\bSystem\s*\.\s*loadLibrary\b", "加载本地库"),
    (r"\bScriptEngine\b|\bRhino\b|\bevaluateJavascript\b", "脚本引擎"),
    (r"\baddJavascriptInterface\b", "JS 桥接"),
    (r"loadUrl\s*\(\s*[\"']javascript:", "JS 注入"),
    (r"\bSerializable\b|\breadObject\b|\bwriteObject\b", "Java 反序列化"),
]

# 网络出口（应统一走 core/net/Http.java）
NET_EXIT = [
    (r"\bnew\s+URL\s*\(", "直接 new URL"),
    (r"\bopenConnection\s*\(", "直接 openConnection"),
    (r"\bOkHttpClient\b", "OkHttp"),
    (r"\bnew\s+Socket\s*\(", "裸 Socket"),
    (r"\bHttpURLConnection\b", "HttpURLConnection 直接使用"),
]

# 权限白名单：除 INTERNET 外一律报 BLOCK
ALLOWED_PERMISSIONS = {"android.permission.INTERNET"}

# 允许的第三方依赖（其余一律 WARN）
KNOWN_DEPS = {
    "androidx.appcompat:appcompat",
    "androidx.recyclerview:recyclerview",
    "androidx.constraintlayout:constraintlayout",
    "com.google.android.exoplayer:exoplayer",
}

BINARY_EXT = (".jar", ".dex", ".so", ".apk", ".aar", ".class", ".exe", ".dll")

SKIP_DIRS = {".git", "out", "build", ".gradle", "node_modules", "__pycache__"}
SOURCE_EXT = (".java", ".kt", ".gradle", ".xml", ".json", ".py")


def load_allowlist():
    path = os.path.join(ROOT, "tools", "audit-allowlist.txt")
    items = {}
    if not os.path.exists(path):
        return items
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|")
            if len(parts) >= 2:
                items[parts[0].strip()] = parts[1].strip()
    return items


def is_binary(path):
    return path.lower().endswith(BINARY_EXT)


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def scan(allowlist):
    blocks, warns, infos = [], [], []
    file_count = 0
    line_count = 0
    domains = set()
    ips = set()

    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, ROOT)
            if os.path.abspath(path) == os.path.abspath(__file__):
                continue  # 不审计自己
            if is_binary(path):
                blocks.append(("BLOCK", rel, 0, "仓库里出现了二进制可执行文件: " + name))
                continue
            if not name.lower().endswith(SOURCE_EXT):
                continue
            file_count += 1
            text = read_text(path)
            lines = text.splitlines()
            line_count += len(lines)

            for i, line in enumerate(lines, 1):
                stripped = line.strip()
                key = "%s:%d" % (rel, i)
                allow_reason = allowlist.get(key)
                is_comment = stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*")

                # core/cli 是桌面命令行工具：它需要 ProcessBuilder 启动本机播放器。
                # 该包已被 app/build.gradle 显式排除，不会进入 APK。
                is_desktop_cli = "/core/cli/" in rel.replace(os.sep, "/")

                for pattern, label in CODE_EXEC:
                    hit = re.search(pattern, line)
                    if not hit:
                        continue
                    if is_comment:
                        # 注释/文档里提到高危 API 本身不构成风险，只作提示
                        infos.append(("INFO", rel, i, "注释中提到 %s（非调用）" % label))
                        continue
                    if is_desktop_cli and label == "执行本地命令":
                        infos.append(("INFO", rel, i,
                                      "桌面 CLI 启动本机播放器（该包已被 APK 构建排除）: %s" % stripped[:90]))
                        continue
                    if allow_reason:
                        infos.append(("INFO", rel, i, "已审查豁免(%s): %s" % (allow_reason, stripped[:110])))
                    else:
                        blocks.append(("BLOCK", rel, i, "疑似 %s -> %s" % (label, stripped[:110])))
                    break

                if rel.endswith("core/net/Http.java") or rel.endswith("core\\net\\Http.java"):
                    continue  # 唯一允许直接出网的文件
                for pattern, label in NET_EXIT:
                    if re.search(pattern, line):
                        if allow_reason:
                            infos.append(("INFO", rel, i, "已审查豁免(%s): %s" % (allow_reason, stripped[:110])))
                        else:
                            warns.append(("WARN", rel, i, "绕过守卫的网络出口(%s) -> %s" % (label, stripped[:110])))
                        break

                # 硬编码 IP：私有/保留段通常是黑名单或测试夹具，公网 IP 才需要警惕
                for m in re.finditer(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", line):
                    ip = m.group(0)
                    if ip in ("0.0.0.0", "127.0.0.1", "255.255.255.255"):
                        continue
                    octets = [int(x) for x in ip.split(".")]
                    private = (octets[0] in (10, 127)
                               or (octets[0] == 192 and octets[1] == 168)
                               or (octets[0] == 172 and 16 <= octets[1] <= 31)
                               or (octets[0] == 169 and octets[1] == 254)
                               or octets[0] >= 224)
                    if private:
                        infos.append(("INFO", rel, i, "私有/保留段 IP %s（多为黑名单或测试夹具）" % ip))
                    else:
                        ips.add((rel, i, ip))
                # 域名（只收带点号的，过滤掉测试里的占位短串）
                for m in re.finditer(r"https?://([A-Za-z0-9._\-]*[.][A-Za-z0-9._\-]+)", line):
                    host = m.group(1).lower()
                    if host.strip("."):
                        domains.add(host)
                # 超长 base64/hex 串（可能是夹带）
                for m in re.finditer(r"\b[A-Za-z0-9+/=]{160,}\b", line):
                    if allow_reason:
                        continue
                    warns.append(("WARN", rel, i, "超长编码串(长度 %d)，需确认用途" % len(m.group(0))))

    # AndroidManifest 权限
    manifest = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
    if os.path.exists(manifest):
        text = read_text(manifest)
        for m in re.finditer(r"<uses-permission[^>]*android:name=\"([^\"]+)\"", text):
            perm = m.group(1)
            if perm not in ALLOWED_PERMISSIONS:
                blocks.append(("BLOCK", "AndroidManifest.xml", 0, "申请了白名单外的权限: " + perm))
            else:
                infos.append(("INFO", "AndroidManifest.xml", 0, "权限: " + perm))

    # gradle 依赖
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if not name.endswith(".gradle"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), ROOT)
            for i, line in enumerate(read_text(os.path.join(dirpath, name)).splitlines(), 1):
                m = re.search(r"(?:implementation|api)\s+['\"]([^:'\"]+):([^:'\"]+):([^'\"]+)['\"]", line)
                if m:
                    coord = "%s:%s" % (m.group(1), m.group(2))
                    if coord in KNOWN_DEPS:
                        infos.append(("INFO", rel, i, "已知依赖 %s:%s" % (coord, m.group(3))))
                    else:
                        warns.append(("WARN", rel, i, "未知依赖 %s:%s，请确认来源" % (coord, m.group(3))))

    for rel, i, ip in sorted(ips):
        warns.append(("WARN", rel, i, "硬编码 IP 地址: " + ip))
    if domains:
        infos.append(("INFO", "-", 0, "配置/代码中出现的域名（请逐个确认是否可信）: " + ", ".join(sorted(domains))))
    infos.append(("INFO", "-", 0, "扫描文件 %d 个，代码行数 %d" % (file_count, line_count)))
    return blocks, warns, infos


def main():
    allowlist = load_allowlist()
    blocks, warns, infos = scan(allowlist)

    def dump(title, rows, limit=None):
        print("\n== %s (%d) ==" % (title, len(rows)))
        if not rows:
            print("  无")
            return
        shown = rows if limit is None else rows[:limit]
        for level, rel, line, msg in shown:
            loc = rel if not line else "%s:%d" % (rel, line)
            print("  [%s] %s | %s" % (level, loc, msg))
        if limit is not None and len(rows) > limit:
            print("  ... 另有 %d 条" % (len(rows) - limit))

    dump("BLOCK 必须处理", blocks)
    dump("WARN 需要人工确认", warns)
    dump("INFO 参考信息", infos, limit=40)

    print("\n" + "-" * 60)
    if blocks:
        print("审计未通过：存在 %d 个必须处理的问题" % len(blocks))
        sys.exit(1)
    print("审计通过：未发现远程代码执行、命令执行、越权权限或夹带二进制。")
    print("注意：静态审计只能证明'没有这些调用'，不能证明'逻辑一定正确'；")
    print("      第三方依赖（ExoPlayer）自身的漏洞不在本脚本覆盖范围内。")
    sys.exit(0)


if __name__ == "__main__":
    main()
