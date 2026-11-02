# 怎么得到 APK（小白版）

> 前提：APK 必须用 **Android Studio** 编译，它会自动下载需要的工具。
> 全程点鼠标，不用敲命令。第一次会比较慢（要下载约 2-3 GB），之后就快了。

---

## 第 1 步：装 Android Studio

1. 打开 https://developer.android.com/studio
2. 点那个大大的绿色 **Download Android Studio** 按钮
3. 勾选同意协议 → 下载（文件约 1 GB 多）
4. 双击安装包，**一路 Next**，所有选项都用默认的，不要改
5. 装完会自动启动，第一次启动会问你要不要导入设置 → 选 **Do not import** → OK

接下来它会自己下载 Android SDK，看到进度条就等着（可能十几分钟到半小时，取决于网速）。
全部跑完出现 **Welcome to Android Studio** 界面就可以了。

---

## 第 2 步：把项目文件夹放好

把解压出来的 `safe-tvbox` 文件夹放到一个**简单路径**下，比如：

```
D:\safe-tvbox
```

> ⚠️ 两个坑一定要避开：
> 1. **路径里不要有中文**（不要放在"桌面\新建文件夹"这种地方）——会编译失败
> 2. **不要放在 C 盘用户目录的 OneDrive 同步文件夹里**——容易出权限问题

---

## 第 3 步：打开项目

1. Android Studio 欢迎界面点 **Open**（不是 New Project）
2. 选中 **`safe-tvbox` 这一层文件夹**（就是里面能看到 `app`、`core`、`settings.gradle` 的那一层）
3. 点 **OK**

> ⚠️ 最容易错的地方：不要一路点进去选到 `app` 文件夹。
> 选对了的话，打开后左侧应该能同时看到 `app` 和 `core` 两个目录。

4. 点 **Trust Project**（会弹一个安全提示）

---

## 第 4 步：等它同步（自动的）

打开后右下角会出现进度条，写着 `Gradle Sync` 或者 `Downloading...`，
**什么都别点，就等着**。第一次大概 5-20 分钟。

如果左下角 Build 窗口最后出现：

```
BUILD SUCCESSFUL
```

就成了。

### 同步失败的常见原因

| 提示 | 怎么办 |
|---|---|
| `SDK location not found` | 会有一个蓝色链接 **Set up SDK**，点它，按提示装 |
| `Gradle 下载超时 / Could not resolve` | 网络问题，重试；或挂个加速器再来 |
| 一直卡在 `Downloading` | 耐心等，第一次确实慢；超过 40 分钟没动静再考虑换网络 |
| `Unsupported Java` | 一般不会出现，AS 自带 Java，别动它 |

---

## 第 5 步：打出 APK

菜单栏点：

```
Build  →  Build Bundle(s) / APK(s)  →  Build APK(s)
```

然后等。完成后右下角会弹一个提示框：

```
APK(s) generated successfully.
```

点提示里的 **locate**，会直接打开 APK 所在的文件夹。

APK 文件在这里：

```
D:\safe-tvbox\app\build\outputs\apk\debug\app-debug.apk
```

**这个 `app-debug.apk` 就是你要的东西。**

---

## 第 6 步：装到设备/盒子上

### 方法 A：U 盘（最简单）
1. 把 `app-debug.apk` 拷到 U 盘
2. U 盘插到电视/盒子上
3. 用盒子上的"文件管理器"找到它，点一下安装
4. 如果提示"未知来源" → 去设置里允许安装未知应用

### 方法 B：adb（如果你会用）
```bash
adb connect 盒子IP:5555
adb install app-debug.apk
```

### 方法 C：先看效果不想装设备
Android Studio 顶部有个设备下拉框 → **Create Device** → 随便选个 TV 或手机 →
点绿色三角 ▶ 就能在模拟器里跑起来看效果。

---

## 一句话总结

> 装 Android Studio → Open 选中 `safe-tvbox` 文件夹 → 等同步完 →
> Build → Build APK(s) → 拿到 `app-debug.apk`

---

## 附：为什么我不直接给你编好的 APK？

两件事说清楚：

1. **我这边编不了**：编译 APK 需要下载 Android SDK 和依赖库（几个 GB），
   我所在的环境访问这些下载源被网络策略挡住了。
2. **就算我给了你也不该信**：APK 是二进制，你看不见里面是什么。
   整个项目的意义就是"代码能被完整读完、自己验证没有后门"——
   你自己编出来的，才是可信的。

这也是为什么 `build.gradle` 里写了 `minifyEnabled false`（不混淆）：
就是为了让你反编译 APK 时还能看到接近源码的结构。

想验证 APK 里确实没有桌面命令行那部分代码，可以解压 APK 看看 `classes*.dex` 里
有没有 `com/tvbox/core/cli` —— 正常应该没有（`build.gradle` 里已经显式排除了）。
