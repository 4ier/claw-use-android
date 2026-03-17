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
