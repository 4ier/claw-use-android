# Claw Use — Learned Flows

Agent 优先查阅此文件，匹配则直接调用 `POST /flow` 或批量 `/act`，跳过逐步推理。
执行新场景后，将可复用的流程追加到此文件。

格式：每个 flow 是一个 JSON 对象，包含 app、flow 名称、描述、acts 数组。
acts 中的每一步可以是：
- `/flow` 的 step 格式：`{"wait":"文本","then":"tap","timeout":N,"optional":bool}`
- `/act` 请求体：`{"click":"文本"}`, `{"swipe":"up"}`, `{"type":"xxx"}` 等
- 特殊指令：`{"screen":true}` 表示需要读屏判断（退出批量模式，交回 agent 决策）

```json
[
  {
    "app": "com.brave.browser",
    "flow": "download-apk-from-lan",
    "desc": "通过 Brave 浏览器从局域网 HTTP 服务器下载 APK 并安装",
    "acts": [
      {"click": "Brave"},
      {"screen": true, "note": "确认 Brave 已打开，找到地址栏 ref"},
      {"click": "url_bar_ref", "note": "点击地址栏（用 ref）"},
      {"type": "http://<lan-ip>:<port>/<apk-name>"},
      {"screen": true, "note": "点击第一个搜索建议"},
      {"wait": "是否重新下载文件？", "then": "none", "timeout": 3000, "optional": true, "note": "可能出现重复下载确认"},
      {"wait": "下载", "then": "tap", "timeout": 5000, "note": "点击下载按钮"},
      {"wait": "无法安全地下载文件", "then": "none", "timeout": 5000, "optional": true},
      {"wait": "保留", "then": "tap", "timeout": 5000},
      {"wait": "打开", "then": "tap", "timeout": 30000, "note": "等待下载完成后点打开"}
    ]
  },
  {
    "app": "com.miui.packageinstaller",
    "flow": "miui-install-apk",
    "desc": "MIUI 安装 APK 的确认流程（安全警告、权限确认等）",
    "acts": [
      {"wait": "是否允许", "then": "none", "timeout": 5000, "optional": true, "note": "首次安装源授权"},
      {"wait": "允许", "then": "tap", "timeout": 5000, "optional": true},
      {"wait": "继续安装", "then": "tap", "timeout": 15000},
      {"wait": "已了解此应用未经安全检测", "then": "tap", "timeout": 10000, "optional": true},
      {"wait": "继续更新", "then": "tap", "timeout": 15000, "optional": true},
      {"wait": "安装包扫描中", "then": "none", "timeout": 30000, "optional": true, "note": "等待扫描完成"},
      {"wait": "安装", "then": "tap", "timeout": 30000, "note": "最终安装按钮"},
      {"wait": "完成", "then": "tap", "timeout": 60000, "optional": true}
    ]
  },
  {
    "app": "com.android.settings",
    "flow": "grant-app-permissions",
    "desc": "通过设置搜索进入目标 app 的权限页面并授权所有权限",
    "acts": [
      {"launch": "com.android.settings"},
      {"screen": true, "note": "在设置主页，找到搜索栏 ref"},
      {"click": "search_ref", "note": "点击搜索栏"},
      {"type": "<app-name>"},
      {"screen": true, "note": "找到目标 app 搜索结果"},
      {"click": "result_ref", "note": "进入 app 详情"},
      {"click": "应用权限"},
      {"screen": true, "note": "逐个点击每个权限项并授权，需循环处理"}
    ]
  },
  {
    "app": "any",
    "flow": "unlock-and-go-home",
    "desc": "解锁设备并回到桌面",
    "acts": [
      {"endpoint": "/screen/wake"},
      {"endpoint": "/screen/unlock"},
      {"home": true}
    ]
  },
  {
    "app": "app.olauncher",
    "flow": "miui-open-app-from-drawer",
    "desc": "MIUI 上 /launch 不可靠，通过 OLauncher app drawer 滑动查找并打开 app",
    "acts": [
      {"home": true},
      {"swipe": "up", "note": "在桌面上滑打开 app drawer"},
      {"screen": true, "note": "检查目标 app 是否在列表中（按拼音排序）"},
      {"swipe": "up", "note": "如果没找到，继续向上滑动翻页，重复直到找到"},
      {"click": "<app-name>", "note": "找到后点击打开"}
    ]
  },
  {
    "app": "com.brave.browser",
    "flow": "brave-download-file-from-lan",
    "desc": "通过 Brave 从局域网下载文件（实测 MIUI Redmi 流程）",
    "acts": [
      {"note": "先用 miui-open-app-from-drawer 打开 Brave，或从桌面点击"},
      {"click": "url_bar_ref", "note": "点击地址栏（ref 包含当前 URL 文本）"},
      {"type": "<download-url>"},
      {"screen": true, "note": "出现搜索建议列表，点击第一个匹配项的 ref"},
      {"wait": "是否重新下载文件？", "then": "none", "timeout": 3000, "optional": true},
      {"click": "下载_ref", "note": "如果有重复下载确认，点'下载'按钮"},
      {"wait": "无法安全地下载文件", "then": "none", "timeout": 5000, "optional": true},
      {"wait": "保留", "then": "tap", "timeout": 5000, "note": "HTTP 安全警告，点保留"},
      {"wait": "打开", "then": "tap", "timeout": 30000, "note": "下载完成后点打开"}
    ]
  },
  {
    "app": "com.android.settings",
    "flow": "settings-search-and-navigate",
    "desc": "通过设置搜索功能快速定位设置项（比滚动找更可靠）",
    "acts": [
      {"screen": true, "note": "在设置主页，搜索栏 text='搜索系统设置项'，但 click=null"},
      {"click": "search_ref", "note": "用 ref 点击搜索栏（不能用 /click text，因为 click:null）"},
      {"screen": true, "note": "搜索框打开，找到输入框 ref"},
      {"click": "input_ref", "note": "聚焦输入框"},
      {"type": "<search-term>"},
      {"screen": true, "note": "点击搜索结果"}
    ]
  },
  {
    "app": "any",
    "flow": "open-url-in-brave",
    "desc": "在 Brave 浏览器中打开指定 URL",
    "acts": [
      {"click": "Brave"},
      {"screen": true, "note": "确认 Brave 前台"},
      {"click": "url_bar_ref"},
      {"type": "<url>"},
      {"screen": true, "note": "点击第一个建议或回车"}
    ]
  }
]
```

---

## Duolingo 自动刷课

### 题型识别规则

通过 `/screen` 返回的 elements 结构判断题型：

| 题型 | instruction | 特征 |
|------|------------|------|
| 选词翻译（日→中） | "翻译这句话" | prompt 是日文，候选词 `desc` 是中文，`text` 为 null |
| 选词翻译（中→日） | "翻译这句话" | prompt 是中文，候选词 `desc` 是日文假名/汉字 |
| 选词填空 | "选词填空" | 句子 slots 有 `role:"button"`，候选词 `role:null`，全日文 |

### 答题决策

**选词翻译**：根据 prompt 语义，从候选词（desc 字段）中选出正确的词，按目标语语序依次点击 ref。

**选词填空**：句子中有一个空位（缺失的词），从候选词中选语义最匹配的填入。

### 通用答题 flow

```json
[
  {
    "app": "com.duolingo",
    "flow": "duolingo-answer-and-continue",
    "desc": "答完题后的固定流程：检查 → 继续",
    "acts": [
      {"click": "检查"},
      {"wait": "继续", "then": "tap", "timeout": 5000},
      {"screen": true, "note": "检查是否还在课程中（看是否有 instruction 元素）"}
    ]
  },
  {
    "app": "com.duolingo",
    "flow": "duolingo-post-lesson",
    "desc": "课程结束后的奖励/排行榜连续点击流程",
    "acts": [
      {"wait": "领取经验", "then": "tap", "timeout": 5000},
      {"wait": "继续", "then": "tap", "timeout": 5000},
      {"wait": "继续", "then": "tap", "timeout": 5000, "optional": true},
      {"wait": "继续", "then": "tap", "timeout": 5000, "optional": true},
      {"wait": "继续", "then": "tap", "timeout": 5000, "optional": true},
      {"wait": "继续", "then": "tap", "timeout": 5000, "optional": true}
    ]
  },
  {
    "app": "com.duolingo",
    "flow": "duolingo-open-next-lesson",
    "desc": "从主页开始下一课",
    "acts": [
      {"screen": true, "note": "确认在主页（有 Learn Tab），找到 '继续' 按钮"},
      {"click": "继续", "note": "开始当前课程"}
    ]
  }
]
```

### Agent 刷课循环伪代码

```
1. 打开多邻国（从 app drawer 找 '多邻国'，MIUI /launch 不可靠）
2. 如果在主页 → 点 '继续' 开始课程
3. 读屏 → 识别题型
4. 根据题型选答案 → 依次点击 ref
5. 执行 duolingo-answer-and-continue flow
6. 如果出现 '领取经验' → 执行 duolingo-post-lesson flow → 课程结束
7. 否则回到步骤 3
```

### 注意事项
- 候选词有重复（每个词出现两次，ref 不同），选第一组即可（较小的 ref）
- 选词填空的候选词 `click: null` 但仍可通过 `/act {"click": ref}` 点击
- 答错会扣红心（ref 1 desc 显示 "还有 N 颗红心"），25 颗用完课程终止
- 课程结束后会连续弹 3-5 个奖励/排行/任务弹窗，全部点 '继续' 即可
- MIUI 上 `/launch` 打开多邻国不可靠，改用 app drawer 滑动查找

## 沉淀规范

新增 flow 时遵循：
1. `app`: 包名（`any` 表示通用）
2. `flow`: 短横线命名，动词开头
3. `desc`: 一句话描述，中文优先
4. `acts`: 按执行顺序排列
   - 能用 `/flow` step 格式的优先（`wait`+`then`），在设备端执行，零 LLM 开销
   - 需要动态决策的步骤用 `{"screen":true}` 标记断点
   - `note` 字段给 agent 的执行提示
5. 模板变量用 `<angle-brackets>` 标记，agent 执行时替换
