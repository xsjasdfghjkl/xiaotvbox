# SafeTVBox 安全说明

这份文档的目的是让你**能自己验证**"没有后门"，而不是让你相信我说没有。

---

## 1. 威胁模型

要防的不是"国家级别的定向攻击"，而是这类 TVBox 生态里真实发生过的三类问题：

| 威胁 | 具体形态 | 本项目对策 |
|---|---|---|
| **远程代码执行** | 配置里带 `jar/dex/js` 地址，客户端下载后 `DexClassLoader` 加载或 `Rhino` 执行 | 机制上不存在：没有 ClassLoader、没有脚本引擎；配置加载阶段直接拒绝这类字段 |
| **静默下发/更新** | 远端随时改配置，客户端无感知地换掉行为 | 配置只在用户点击"加载"时更新；被拒项在设置页可见 |
| **被当作跳板** | 恶意配置诱导客户端访问内网 / 云元数据 / 任意端口 | 所有出网请求过 `SecurityGuard`：协议、端口、地址段、重定向逐跳校验 |

不覆盖的：ExoPlayer 自身的漏洞、你主动接入的内容源本身的合法性、Root 后的设备。

---

## 2. 被彻底移除的能力（对照同类项目）

源码中不存在以下任何一项，可用 `python3 tools/audit.py` 验证：

- `DexClassLoader` / `PathClassLoader` / `URLClassLoader` / `Class.forName` 动态装载
- `Runtime.getRuntime()` / `ProcessBuilder` / `exec()` 本地命令执行
- `ScriptEngine` / `Rhino` / `evaluateJavascript` / `addJavascriptInterface`
- `System.load` / `System.loadLibrary` 加载 so
- Java 原生反序列化（`readObject`）
- 反射越权（`setAccessible`）
- 静默安装 APK（`REQUEST_INSTALL_PACKAGES`）

配置层还有一道独立闸门（`SiteConfig` + `ConfigLoader`）：

- `type` 为 `0/1/2/3/js/jar/dex/spider` → 直接抛异常拒绝
- 规则树递归扫描，出现 `jar` `dex` `spider` `codeurl` `extendedclass` `eval` 等键 → 拒绝
- 规则文本中出现 `.jar` `.dex` `DexClassLoader` `Runtime.getRuntime` → 拒绝
- 配置顶层出现 `spider` 字段 → 忽略并告警
- 站点数上限 200、配置大小上限 2MB

---

## 3. 主动实现的防护

**模板引擎（`Expr`）不是表达式语言**
只允许 `${变量名}`，变量名只允许 `[A-Za-z0-9_.-]`；
`${1+1}`、`${id.toString()}`、`${@java.lang.Runtime@getRuntime()}` 这类一律**原样回显、不解析**。
管道函数只有编码/哈希/字符串处理，不含文件、网络、反射、类加载。
（对应测试：`expr no-eval *` 系列）

**出网统一收口（`Http` + `SecurityGuard`）**
- 协议白名单：只放行 `http` / `https`
- 地址黑名单：回环、10/8、172.16/12、192.168/16、169.254/16（含云元数据）、100.64/10、IPv6 私有与链路本地、`.local` / `.internal`
- 端口白名单：80/443/8080/8443
- 拒绝 URL 内嵌用户名密码
- 关闭自动重定向，逐跳重新校验，最多 3 跳（防止"外网跳内网"）
- 响应体硬上限，超时硬上限，禁止配置覆盖 `Host` / `Content-Length`
- 全部源码中只有 `core/net/Http.java` 一个文件直接出网

**解析器自身的健壮性**
- JSON 递归深度上限 64（畸形输入不栈溢出）
- HTML 节点数上限 20000（超大页面不 OOM）
- 正则输入长度上限 4MB，结果条数上限 5000
- JSONPath 不做过滤表达式 `$[?(...)]`——那等价于引入一门语言

**播放地址也要过安全闸门**
详情页传来的地址不是直接丢给播放器：先经 `playerContent()` 二次解析并用同一套守卫校验，
内网/非 http(s) 地址会被拒绝并在界面上显示原因。

**权限最小化**
`AndroidManifest.xml` 只申请 `INTERNET`。
没有定位、电话状态、通讯录、存储、录音、摄像头、`QUERY_ALL_PACKAGES`。

**不混淆**
release 构建 `minifyEnabled false`。可信度来自"代码能被完整读完"，不是来自把实现藏起来。

**依赖最小化**
第三方依赖只有 4 个：3 个 androidx UI 库 + ExoPlayer。
图片加载自己写（封面图同样走安全守卫），不引 Glide/Picasso。

---

## 4. 内置内容源的合法性

- `storage.googleapis.com` / `commondatastorage.googleapis.com`：Google 公开的 ExoPlayer、GTV 示例媒体
- `iptv-org.github.io`：GitHub 上的公开广播索引仓库，本项目只做纯文本解析，不转存、不转发、不提供播放服务

**没有内置任何第三方影视聚合接口。** 这类接口的版权状态通常不清晰，也不适合由第三方分发。
接入什么源、是否合法，由你自己决定并负责。

---

## 5. 已知局限（我不藏）

1. **DNS rebinding（TOCTOU）**：`SecurityGuard` 在 DNS 解析后、连接建立前校验，
   理论上存在"校验时是公网 IP、连接时解析到内网"的时间窗。
   彻底解决需要在自定义 Socket 层绑定解析结果，本项目未实现。
   对"自用 TV 客户端 + 手动添加配置"的威胁模型来说，这一层已经足够。

2. **ExoPlayer 自身漏洞**：解码器/协议栈的历史 CVE 不在本项目控制范围。请保持依赖更新。

3. **明文流量**：`usesCleartextTraffic="true"`。很多 IPTV/直播源仍是 http，
   关闭会直接不可用；代价是同局域网内的中间人可以看到你在请求什么。
   如果你的源全部是 https，可以在 Manifest 里把它改成 `false`。

4. **m3u8 内容来自第三方**：播放器会按 m3u8 去取分片，分片域名由远端决定，
   这些请求不经过 `SecurityGuard`（它们是播放器内部发起的）。

5. **静态审计的边界**：`tools/audit.py` 能证明"源码里没有这些调用"，
   不能证明逻辑没有 bug，也不审查 ExoPlayer 内部。

---

## 6. 你自己怎么验

```bash
# 1) 跑引擎测试（115 项断言，含注入尝试与 SSRF 用例）
cd core && java -cp out com.tvbox.core.CoreTest

# 2) 静态审计：远程代码装载 / 命令执行 / 越权权限 / 夹带二进制 / 可疑域名
python3 tools/audit.py

# 3) 体检一份配置（不联网）
java -cp out com.tvbox.core.VerifyConfig app/assets/config/demo-safe.json

# 4) 人工核对：只有这一个文件直接出网
grep -rn "openConnection\|new URL(" --include=*.java . 
```

`tools/audit-allowlist.txt` 目前为空：源码里没有任何一行需要豁免的高危调用。
如果你改代码后出现了 `DexClassLoader` 之类，正确做法是删掉那行代码，而不是往清单里加豁免。
