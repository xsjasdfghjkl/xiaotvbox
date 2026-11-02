# 两条路：先在电脑上验证，再打出 APK

推荐顺序：**先在电脑上把规则调通 → 再打 APK 装盒子**。
因为改一个 JSONPath 就要重编一次 APK 太慢了，而引擎在电脑上跑的是**同一份代码**。

---

## 第一部分：电脑上测试（10 分钟内能跑起来）

### 0. 准备

- **JDK 8 以上**（命令行 `java -version` 有输出即可；只有 JRE 也能跑，脚本会自动切换编译方式）
- **一个播放器**：ffplay（装 ffmpeg 就自带）、mpv、VLC 三选一。程序会自动探测，找不到就只打印地址。

> Windows 上装 ffmpeg 最简单：`winget install ffmpeg` 或去 ffmpeg.org 下载解压后把 `bin` 加进 PATH。
> 什么都不想装也能用：`play` 会打印出真实地址，`save` 会导出 `playlist.m3u`，用你现有的播放器打开即可。

### 1. 编译

```bash
./build.sh
```

（Windows 没有 bash 的话，等价命令是：
`mkdir core\out` 然后
`javac -d core\out core\src\main\java\com\tvbox\core\**\*.java core\src\test\java\com\tvbox\core\*.java`）

### 2. 启动

```bash
./run.sh
```

会进入交互模式，先列出内容源：

```
 1) 官方测试片源（稳定）        key=official_samples  type=static enabled=true
 2) 公开 IPTV 索引（iptv-org）  key=iptv_org_cn       type=decl   enabled=true
 3) 自建接口模板（默认禁用）     key=template_json     type=decl   enabled=false

> 
```

### 3. 常用操作

```
> home 1                  # 看第 1 个源的首页
> detail 1 gtv-blazes     # 看详情（站点key + 影片id）
> play 1 gtv-blazes       # 解析并直接调 ffplay 播放
> search bunny            # 跨源搜索
> verify                  # 体检配置
> exit
```

也可以先 `home 1`，再用列表序号：

```
> home 1
   1) Big Buck Bunny   id=gtv-bunny   MP4 · 596s
   2) Elephants Dream  id=gtv-elephants ...
> detail 3              # 直接用序号，不用记 id
> play 1                # 用上一个详情，播第 1 集
```

### 4. 不用交互，直接单条命令

```bash
java -cp core/out com.tvbox.core.cli.Desktop -c config/demo-safe.json sites
java -cp core/out com.tvbox.core.cli.Desktop -c config/demo-safe.json home official_samples
java -cp core/out com.tvbox.core.cli.Desktop -c config/demo-safe.json --allow-private home iptv_org_cn
```

### 5. 有用的开关

| 开关 | 作用 |
|---|---|
| `-c <路径>` | 指定配置文件，默认 `config/demo-safe.json` |
| `--allow-private` | 放行内网地址。测试你自己 NAS / 局域网源时才需要 |
| `--dump` | 只打印解析结果（含原始 `playUrl`），不启动播放器。**调规则时用这个** |
| `--no-play` | 解析出地址但不播放 |

### 6. 调自己接口的典型流程

1. 复制一份配置：`cp app/assets/config/demo-safe.json config/mine.json`
2. 把 `template_json` 的 `api` 改成你的地址，`enabled` 改成 `true`
3. `java -cp core/out .../Desktop -c config/mine.json --dump home <你的key>`
4. 列表是空的 → 多半是 `root` 路径写错了，用 `--dump` 看原始结构再改
5. 通了之后 `play` 验证能起播
6. **最后** `java -cp core/out com.tvbox.core.VerifyConfig config/mine.json` 体检一遍

沙盒里我这边外网被限制，iptv-org 那条返回 403；你本机网络正常的话是能通的。

---

## 第二部分：打出 APK

### 方式 A：Android Studio（推荐，最省事）

1. 下载 [Android Studio](https://developer.android.com/studio)（自带 JDK 和 Android SDK，不用单独配）
2. 打开 → **File → Open** → 选中 `safe-tvbox` 这一层目录
3. 第一次会联网下 Gradle 和依赖（appcompat / recyclerview / constraintlayout / ExoPlayer），等它跑完
4. 连上盒子或开模拟器 → **Run ▶**
5. 要 APK 文件：**Build → Build Bundle(s) / APK(s) → Build APK(s)**，产物在
   `app/build/outputs/apk/debug/app-debug.apk`

> 坑位提醒：Open 的时候要选**外层 `safe-tvbox` 目录**（里面有 `settings.gradle`），
> 不要直接选 `app/`，否则 Gradle 找不到项目结构。

### 方式 B：命令行（不推荐小白用）

先配好 Android SDK（`ANDROID_HOME` 或 `local.properties` 里写 `sdk.dir=...`），
再装 Gradle 8.x，然后：

```bash
gradle assembleDebug
```

产物同样是 `app/build/outputs/apk/debug/app-debug.apk`。

> 本仓库**没有**附带 `gradlew` / `gradle-wrapper.jar`（那是个二进制文件，我无法生成）。
> 所以请不要执行 `./gradlew`，会直接失败。用 Android Studio 打开即可，它会自己处理 Gradle。

要签名发布版：

```bash
gradle assembleRelease
```

默认没有配签名，会生成未签名的 `app-release-unsigned.apk`，自己用 `apksigner` 签一下即可
（自用学习没必要折腾，直接装 debug 版就行）。

### 装到盒子 / 电视上

```bash
adb connect <盒子IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

或者把 APK 拷到 U 盘，插盒子上用文件管理器装。

---

## 关于 `sourceSets` 的一个坑（已经处理好了）

`app/build.gradle` 里是这样引用核心引擎的：

```groovy
java.srcDirs += ['../core/src/main/java']
java.excludes += ['com/tvbox/core/cli/**']
```

- 路径是 `../core`（相对 `app/` 目录），写成 `../../core` 会找不到
- `cli/**` 被显式排除：**桌面命令行工具（里面有用 `ProcessBuilder` 启动本机播放器的代码）不会进 APK**

所以 APK 里只有：解析引擎 + Android UI + ExoPlayer，`ProcessBuilder` 一行都没有。
可以用 `python3 tools/audit.py` 验证，也可以解压 APK 看 classes 里有没有 `core/cli`。

---

## 常见报错对照表

| 报错 | 原因 |
|---|---|
| `找不到配置文件: config/demo-safe.json` | 先 `mkdir -p config && cp app/assets/config/demo-safe.json config/` |
| `请求失败 HTTP 403` | 目标站点拒绝了默认 UA，在配置里加 `headers: {"User-Agent": "..."}` |
| `域名解析失败` | 本机 DNS 问题，或域名确实不存在 |
| `播放地址被安全策略拒绝` | 地址是内网或非 http(s)。测试自己的局域网源加 `--allow-private` |
| `没找到可用播放器` | 装 ffmpeg / mpv / VLC，或者用 `--dump` 拿地址手动播 |
| Android Studio 报 `Plugin [id: 'com.android.application'] not found` | 打开了 `app/` 而不是外层目录 |
| Gradle 下载超时 | 换网络，或给 Gradle 配国内镜像 |
