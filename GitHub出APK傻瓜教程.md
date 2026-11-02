# 用 GitHub 自动编 APK（不用装 Android Studio）

> 好处：**不用下载几个 GB 的 Android Studio**，也不用配任何环境。
> 代码传到 GitHub，它云端帮你编译，编完你在网页上直接下载 APK。
> 全程网页操作 + 一次上传代码。

预计耗时：注册 5 分钟 + 上传 5 分钟 + 等编译 10 分钟。

---

## 第 0 步：先知道你要做什么

```
你的电脑                        GitHub 云端                     你拿到的东西
   │                                │                              │
   │  ① 上传 safe-tvbox 文件夹  ──▶  │                              │
   │                                │  ② 自动编译（约 5-10 分钟）   │
   │                                │                        ──▶  app-debug.apk
   │  ③ 网页点一下下载          ◀──  │                              │
```

---

## 第 1 步：注册 GitHub

1. 打开 https://github.com
2. 点右上角 **Sign up**
3. 填邮箱、密码、用户名 → 按提示完成人机验证
4. 去邮箱点验证链接（**一定要验证，不然用不了 Actions**）

---

## 第 2 步：新建一个仓库

1. 登录后点右上角 **+** → **New repository**
2. 填写：
   - **Repository name**：`safe-tvbox`（随便起也行）
   - **Public / Private** → **选 Public（公开）** ⚠️
   - 下面三个勾选项 **全部不要勾**（不要 README、不要 .gitignore、不要 license）
3. 点 **Create repository**

> ⚠️ 为什么要选 Public：
> 公开仓库的 GitHub Actions **完全免费且不限时长**；私有仓库每月只有 2000 分钟额度，
> 编一次 Android 大约要 5-10 分钟，虽然够用但没必要浪费。
> 这项目里没有任何你的私密信息，公开没关系。

建好后你会看到一个全是命令的页面，**先别管它**，往下走。

---

## 第 3 步：把代码传上去

挑一个你觉得顺手的方式：

### 方式 A：网页拖拽（最简单，不用装任何东西）

1. 在你刚建的仓库页面，找到中间那行蓝色的
   **"or upload an existing file"** → 点 **uploading an existing file**
   > 或者直接访问：`https://github.com/你的用户名/safe-tvbox/upload`

2. 打开你电脑上解压出来的 `safe-tvbox` 文件夹，**全选里面所有东西**
   （`app`、`core`、`.github`、`build.gradle`、`README.md`…… 全部）
   **拖到网页那个虚线框里**

3. 等等待上传完（文件不多，很快）

4. 页面底部 **Commit changes** 按钮，点它

> ⚠️ 两个注意点：
> - 拖进去的是 `safe-tvbox` **里面的内容**，不是 `safe-tvbox` 文件夹本身。
>   传完之后仓库首页应该能直接看到 `app`、`core`、`README.md` 这几个并列的项。
>   如果你看到的是只有 `safe-tvbox` 一个文件夹，说明拖错了，删掉重来。
> - 隐藏文件夹 `.github` 可能会被浏览器的文件选择框隐藏。
>   如果上传后发现没有 `.github` 文件夹，改用下面的方式 B 或 C。

---

### 方式 B：GitHub Desktop（图形界面，推荐不习惯命令行的人）

1. 下载安装 https://desktop.github.com
2. 打开 → 登录你的 GitHub 账号
3. **File → Add Local Repository** → 选择你电脑上的 `safe-tvbox` 文件夹
4. 如果提示 "not a git repository" → 点 **Create a repository**，直接用默认设置创建
5. 点顶部 **Publish repository** → 名字填 `safe-tvbox`，**取消勾选 Keep this code private**
6. 点 **Publish Repository**

搞定，代码就上去了。

---

### 方式 C：命令行（如果你会一点 git）

在 `safe-tvbox` 目录下执行：

```bash
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin https://github.com/你的用户名/safe-tvbox.git
git push -u origin main
```

---

## 第 4 步：开启 Actions

1. 回到你的仓库页面，点顶部的 **Actions** 标签
2. 如果是第一次用，会看到一段提示，点绿色的
   **"I understand my workflows, go ahead and enable them"**
3. 左侧列表里应该能看到一个叫 **「构建 APK」** 的工作流

---

## 第 5 步：启动编译

1. 左侧点 **「构建 APK」**
2. 右边会有一个 **Run workflow ▾** 按钮（灰色的下拉）→ 点它 → 再点绿色的 **Run workflow**
3. 页面刷新，列表里出现一条正在跑的任务（黄色圆点 🟡 表示在跑）
4. **点进去可以看实时日志**，第一次大概 5-10 分钟

等到变成 ✅ 绿色对勾，就编译成功了。

---

## 第 6 步：下载 APK

1. 点进那次成功的运行记录（绿色的那条）
2. 页面往下拉，最底下有个 **Artifacts** 区域
3. 点 **SafeTVBox-APK** → 浏览器开始下载一个 `.zip`
4. 解压 → 里面是：

   - **`SafeTVBox-debug-xxxx.apk`** ← **装这个**（已签名，直接能装）
   - `SafeTVBox-release-unsigned-xxxx.apk` ← 未签名发布版，一般不用管

> 产物只保留 30 天，过期就没了，需要的话重新跑一次。

---

## 第 7 步：装到盒子/电视上

把 `SafeTVBox-debug-*.apk` 拷到 U 盘 → 插到电视/盒子 → 用文件管理器点开安装。
提示"未知来源"就去设置里允许一次。

---

## 以后改了配置，怎么重新出包？

不需要重来一遍。在 GitHub 网页上：

1. 进仓库 → 找到 `app/assets/config/demo-safe.json`
2. 点右上角铅笔图标 ✏️ 编辑
3. 改完拉到最下面点 **Commit changes**
4. 再进 Actions → Run workflow → 等几分钟 → 下载新的 APK

或者本地改完用 GitHub Desktop 的 **Push origin** 推上去，也会自动触发编译。

---

## 出问题了怎么查

**Actions 里没看到「构建 APK」这个工作流**
→ 说明 `.github/workflows/build-apk.yml` 没传上去（隐藏文件夹容易被漏掉）。
→ 解决：去仓库首页看有没有 `.github` 文件夹；没有就改用方式 B 或 C 重新传。

**运行记录是 ❌ 红色**
→ 点进去看红色的错误行，把报错内容复制给我，我帮你看。
→ 最常见的三类：
  - `Plugin [id: 'com.android.application'] not found` → 根 `build.gradle` 没传上去
  - `SDK location not found` → 少见，重试一次通常就好
  - `Could not resolve com.google.android.exoplayer` → 网络抖动，点右上角 **Re-run** 重试

**Artifacts 区是空的**
→ 编译其实失败了，往上看日志。

---

## 附：工作流做了什么（想了解可以看）

文件在 `.github/workflows/build-apk.yml`，步骤是：

1. 装 JDK 17 + Gradle 8.2
2. `gradle assembleDebug` → 出 debug APK
3. 把 APK 重命名（带时间戳）打包成产物上传

不用改它，默认就能跑。
