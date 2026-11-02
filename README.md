# SafeTVBox

一个**用声明式配置代替远程代码**的 TVBox 类客户端，只用于个人技术学习。

它的存在意义不是"再给你一个能看片的盒子"，而是回答一个问题：

> 如果把同类项目里"从远端下载 JS / jar 并在本地执行"的那层彻底拿掉，还剩下多少能力是可以安全实现的？

答案是：绝大部分。分类、列表、详情、搜索、多源播放都能用**纯数据规则**描述，
代价只是不能适配少数必须靠脚本才能解析的站点——而那一层恰好也是所有后门与灰产的入口。

---

## 快速上手

- **想在电脑上先试规则、看能不能播** → 见 [BUILD.md](BUILD.md) 第一部分，`./build.sh && ./run.sh`，十分钟能跑起来
- **想要 APK（小白一步步版）** → 见 [APK傻瓜教程.md](APK傻瓜教程.md)，全程点鼠标
- **想要 APK（有经验）** → 见 [BUILD.md](BUILD.md) 第二部分，Android Studio 打开 `safe-tvbox` 目录即可
- **安全设计与已知局限** → 见 [SECURITY.md](SECURITY.md)
- **规则怎么写** → 见 [config/schema.md](config/schema.md)

---

## 一、它和原版 TVBox / CatVod 的关系

| 能力 | 原版 | 本项目 |
|---|---|---|
| `type=0` 继承 Spider 的 Java 类 | ✅ | ❌ 不实现（等价于让远端决定本地行为） |
| `type=1` 远程 JS（`Rhino` 执行） | ✅ | ❌ 不实现，用模板 + 白名单函数替代 |
| `type=2` 远程 jar / dex（`DexClassLoader`） | ✅ | ❌ **在配置加载层直接拒绝** |
| `type=3` XPath 宏 | ✅ | ⚠️ 用 JSONPath / CSS 选择器子集替代 |
| JSON / HTML / M3U 解析 | ✅ | ✅ |
| 自定义请求头、Referer、UA | ✅ | ✅ |
| 多播放源、多集 | ✅ | ✅ |
| 播放器 | IJK / Exo | ExoPlayer（唯一第三方功能依赖） |

Spider 的五个方法沿用同一套命名，方便对照原版源码阅读：
`homeContent` / `categoryContent` / `detailContent` / `searchContent` / `playerContent`。

---

## 二、目录结构

```
safe-tvbox/
├── core/                     # 解析引擎（纯 JDK，零第三方依赖，可独立编译与测试）
│   └── src/main/java/com/tvbox/core/
│       ├── json/            # 自写 JSON 解析（带深度/长度上限，无反序列化绑定）
│       ├── path/            # JSONPath 受限子集（无过滤表达式、无脚本）
│       ├── html/            # HTML DOM + CSS 选择器子集（不执行 script）
│       ├── expr/            # 模板渲染：只有变量替换 + 白名单管道函数
│       ├── net/             # Http 客户端 + SecurityGuard（SSRF 防护）
│       ├── model/           # 数据模型与站点配置（含危险类型拒绝逻辑）
│       └── parse/           # DeclSpider / ConfigLoader / M3uParser
│   └── src/test/java/       # 115 项测试（含本地 HTTP 服务端到端）
├── app/                      # Android 客户端（Java）
│   ├── src/main/java/com/tvbox/app/   # 5 个页面 + 配置仓库 + 图片加载
│   ├── src/main/res/                  # 布局与主题
│   └── assets/config/demo-safe.json   # 内置：官方测试片源 + 公开 IPTV 索引 + 自填模板
├── tools/audit.py            # 静态安全审计脚本
├── config/                   # 规则语法文档与示例
├── README.md
└── SECURITY.md
```

**Android 端直接把 `core/src/main/java` 加进 sourceSets**，跑的是同一份解析引擎——
不存在"手机端一套、桌面端一套"，也就不存在移动端被夹带的可能。

---

## 三、内置了什么"稳定接口"

我没有内置任何第三方影视聚合接口（这类接口的版权状态通常不清晰，也不适合由我分发）。
内置的三个源都是**公开、合法、可长期访问**的：

1. **官方测试片源（static）** — Google 公开的 ExoPlayer / GTV 示例媒体：
   - `commondatastorage.googleapis.com/gtv-videos-bucket/sample/*.mp4`
   - `storage.googleapis.com/exoplayer-test-media-0/...`（含 HLS 多码率 master.m3u8）
   
   作用：装好就能验证"解析 → 详情 → 起播"整条链路是否通，与任何第三方站点无关。

2. **公开 IPTV 索引（iptv-org）** — GitHub 上的公开广播索引仓库（`iptv-org.github.io`），
   以纯文本 m3u 解析，不做转存、不做转发。用于验证 `parse: m3u` 链路。

3. **自建接口模板（默认禁用）** — 一个完整的 `decl` 规则骨架，`api` 指向 `example.com`，
   你把自己的接口地址填进去、把 `enabled` 改成 `true` 即可。

---

## 四、跑起来

### 0. 桌面上直接试（推荐先做这个）

```bash
./build.sh          # 编译，纯 JDK，不需要 Android
./run.sh            # 进入交互模式：列源 / 看列表 / 看详情 / 搜索 / 开播
```

`play` 会自动探测本机的 ffplay / mpv / VLC 并播放；没有播放器也能用 `--dump` 打印真实地址，
或 `save` 导出 `playlist.m3u`。详情见 [BUILD.md](BUILD.md)。

### 1. 核心引擎：编译 + 跑测试（不需要 Android 环境）

```bash
cd core
mkdir -p out
javac -d out $(find src/main/java -name '*.java') $(find src/test/java -name '*.java')
java -cp out com.tvbox.core.CoreTest
```

如果你的机器上只有 JRE 没有 `javac`，可以用 JDK 内置的编译器模块：

```bash
java -m jdk.compiler/com.sun.tools.javac.Main -d out $(find src/main/java -name '*.java')
```

测试会自己起一个本地 HTTP 服务，跑完 115 项断言，覆盖：
JSON 深度限制、JSONPath、CSS 选择器、模板注入尝试（`${1+1}`、`${@Runtime@getRuntime()}` 原样回显）、
SSRF 黑名单、配置准入（type=1/2 被拒）、响应体上限、重定向拦截、Spider 五方法端到端。

### 2. 校验一份配置是否安全

```bash
java -cp out com.tvbox.core.VerifyConfig app/assets/config/demo-safe.json
```

不联网，只做静态体检：站点是否准入、URL 是否会被守卫放行、有没有夹带 jar/dex 字段。

### 3. Android 客户端

Android Studio → *Open* → 选中 `safe-tvbox` 目录 → 直接 Run。
Gradle 会拉取三个 androidx 依赖和 ExoPlayer，除此之外没有任何依赖。

### 4. 静态安全审计

```bash
python3 tools/audit.py
```

扫描所有源码与配置，检查远程代码装载、命令执行、反射、越权权限、
绕过守卫的网络出口、夹带二进制、可疑域名。当前结果：0 BLOCK / 0 WARN。

---

## 五、添加你自己的内容源

规则是**数据**，写在 JSON 里。核心是 5 个规则块：`home` / `category` / `detail` / `search` / `play`。

```json
{
  "key": "my_site",
  "name": "我的接口",
  "type": "decl",
  "api": "https://your.domain/api",
  "headers": { "User-Agent": "SafeTVBox/1.0" },
  "rules": {
    "home": {
      "url": "${api}/index.php?vod/home",
      "method": "GET",
      "parse": "json",
      "classes": {
        "root": "$.class",
        "fields": { "type_id": "$.type_id", "type_name": "$.type_name" }
      },
      "list": {
        "root": "$.list",
        "fields": {
          "vod_id": "$.vod_id",
          "vod_name": "$.vod_name",
          "vod_pic": "$.vod_pic",
          "vod_remarks": "$.vod_remarks"
        }
      }
    }
  }
}
```

字段取值写法（详见 `config/schema.md`）：

| 写法 | 含义 |
|---|---|
| `$.list[0].vod_name` | JSONPath（支持 `[n]` `[*]` `[a:b]` `..key`） |
| `css:ul.vod li a@href` | CSS 选择器取属性（`@text` `@html` `@outerHtml` 或属性名） |
| `re:name\":\"(.*?)\"@1` | 正则取第 N 组 |
| `直接写字` | 常量，里面的 `${var}` 会做变量替换 |

URL 模板里的变量：`${api}` `${tid}` `${page}` `${id}` `${wd}` `${flag}` 以及 `ext` 里自定义的键。
可用的管道函数只有：`urlencode` `urldecode` `base64` `base64d` `md5` `upper` `lower` `trim`
`len` `int` `concat:x` `replace:a:b` `sub:a:b`。**没有字符串之外的任何能力。**

加载后如果你的配置里有危险字段（`.jar` `.dex` `DexClassLoader` 之类），
设置页会明确列出"被拒绝的内容源及原因"，不会静默丢弃，也不会执行。

---

## 六、几个诚实的说明

- 这是**学习项目**，不是成品播放器。UI 很朴素，没有历史记录、收藏、多屏适配之类。
- 我不提供、也不内置第三方影视聚合接口地址。请自行确认你接入的内容源有合法授权。
- 静态审计能证明"没有这些调用"，不能证明逻辑一定没有 bug。
- ExoPlayer 自身的历史 CVE 不在本项目控制范围内，保持依赖更新是你的责任。

更完整的安全说明见 `SECURITY.md`。
