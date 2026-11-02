# 配置规则语法（schema）

> 规则是**数据**，不是代码。任何一份配置都无法让客户端执行任何东西——
> 它能做的只是"告诉客户端去哪个 URL 取数据、用哪条路径取字段"。

## 1. 顶层结构

```json
{
  "sites": [ { ...站点1... }, { ...站点2... } ]
}
```

也接受顶层直接是数组。顶层出现 `spider` 字段会被忽略并告警。

## 2. 站点字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `key` | ✅ | 唯一标识 |
| `name` | ✅ | 显示名 |
| `type` | | `static`（内置静态列表）或 `decl`（声明式规则），默认 `decl` |
| `api` | | 接口根地址，会注入模板变量 `${api}` |
| `enabled` | | 默认 `true`，`false` 则不加载 |
| `searchable` | | 默认 `true`，是否纳入全局搜索 |
| `headers` | | 请求头，值里可以用 `${var}` |
| `ext` | | 自定义变量，会注入模板上下文 |
| `rules` | | `decl` 类型的规则块 |
| `static` | | `static` 类型的静态内容 |

**`0` / `1` / `2` / `3` / `js` / `jar` / `dex` / `spider` 这些 type 会被直接拒绝**
（它们是同类项目里远程代码执行的入口）。

## 3. 规则块（rules）

五个键：`home` / `category` / `detail` / `search` / `play`。

### 通用字段

| 字段 | 说明 |
|---|---|
| `url` | 请求地址模板；留空则用站点 `api` |
| `method` | `GET`（默认）或 `POST` |
| `headers` | 覆盖/追加请求头 |
| `body` | POST 内容；对象会被编码成 `k=v&k2=v2`，字符串则原样发送 |
| `parse` | `json`（默认）/ `html` / `regex` / `m3u` |
| `root` | 列表根节点：json 用 JSONPath，html 用 CSS 选择器，regex 用正则 |
| `fields` | 字段 → 取值表达式，见第 4 节 |
| `limit` | `parse=m3u` 时的条数上限，默认 200 |

### home 额外支持嵌套 `classes`

```json
"home": {
  "url": "${api}/home.json",
  "parse": "json",
  "classes": {
    "root": "$.class",
    "fields": { "type_id": "$.type_id", "type_name": "$.type_name" }
  },
  "list": {
    "root": "$.list",
    "fields": { "vod_id": "$.id", "vod_name": "$.title" }
  }
}
```

### detail 的播放源与多集

- `vod_play_from`：多个源用 `$$$` 分隔（如 `m3u8$$$yun`）
- `vod_play_url`：与 `play_from` 一一对应，同样用 `$$$` 分隔；
  单个源内部多集用 `#` 分隔，单集形如 `第1集$url1`

字段名做了同义兼容：`title/name` → `vod_name`，`pic/cover/img` → `vod_pic`，
`desc/content` → `vod_content`，`from/play_from` → `vod_play_from` 等。

### play（二次取播放地址）

```json
"play": {
  "url": "${api}/play?id=${id}",
  "parse": "json",
  "url_field": "$.url",
  "headers": { "Referer": "${api}/" }
}
```

若省略 `play` 块，则详情页传来的地址会被直接使用（但仍经安全守卫校验）。

## 4. 字段取值表达式

| 前缀 | 示例 | 说明 |
|---|---|---|
| `$` | `$.data.list[0].vod_name` | JSONPath 子集 |
| `css:` | `css:div.item a@href` | CSS 选择器 + 属性 |
| `re:` | `re:title\":\"(.*?)\"@1` | 正则取第 N 组 |
| 无前缀 | `固定值${tid}` | 常量，其中的 `${var}` 会替换 |

### JSONPath 支持范围

```
$           根
.name       字段
['name']    字段（引号形式）
[0]         下标，负数表示倒数
[*]         所有元素
[0:3]       切片
..name      递归下降
```

**不支持** `$[?(@.x)]` 这类过滤表达式——它等价于引入一门表达式语言，是注入的常见载体。

### CSS 支持范围

```
tag  #id  .class  [attr]  [attr=value]  *
空格（后代）  >（直接子）  ,（并集）
:nth-child(n)   :eq(n)   :eq(last)
```

`@` 后面可以是任意属性名，或 `text` / `html` / `outerHtml`（默认 `text`）。

## 5. 模板变量

| 变量 | 含义 |
|---|---|
| `${api}` | 站点 api |
| `${key}` `${name}` | 站点 key / name |
| `${tid}` `${page}` | 分类 id / 页码（category） |
| `${id}` | 影片 id（detail）或播放 id（play） |
| `${wd}` `${keyword}` | 搜索词（search） |
| `${flag}` | 播放源标记（play） |
| `${ext里定义的键}` | 自定义变量 |

## 6. 管道函数（白名单，仅此 13 个）

```
urlencode   urldecode   base64   base64d   md5
upper       lower       trim     len       int
concat:x    replace:a:b sub:a:b
```

用法：`${wd|urlencode}`、`${id|md5}`、`${title|sub:0:10}`。

**没有** 文件读写、网络请求、反射、类加载、时间随机数之外的任何能力。
未知函数名不会报错，也不会执行——直接返回原值。

## 7. 完整示例

见 `app/assets/config/demo-safe.json`（含 static 源、m3u 源、JSON 接口模板）。
