# AI 正文清洗（补丁式）设计

- 日期：2026-11-18
- 状态：设计定稿，待评审后进入实施
- 适用仓库：`D:\Project\legado`（Android 客户端 + Django 后端）

## 1. 背景

书源为了规避版权/推广，常对正文做四类破坏：

1. 插入广告、网址、公众号、QQ 群、求票、站点水印；
2. 插入乱码、零宽字符、全半角混排、把字拆开；
3. 删字、错字、谐音替换，造成句子断裂；
4. **付费残缺 + 拼接**：源站只放出付费章节的前几段，另一站点在这几段后拼接别的小说内容来凑字数。

前三类可以清洗；第四类不能润色，必须甄别后整章放弃，否则模型会把拼接内容当成正文去“修”，产生更严重的幻觉。

## 2. 目标

- 删除广告/网址/水印/乱码/零宽字符；
- 修补缺字、错字、被拆字与断裂句；
- 识别并修复源级系统性缺字/换字（一次学习、全局应用，避免逐章烧钱）；
- 甄别“付费残缺 + 拼接”，整章放弃润色并提示换源；
- 只在 DeepSeek 空闲时段调用，按用户/日预算封顶；
- 每一处修改可审计、可回退；
- 客户端直接改写缓存文件，阅读时零额外请求。

## 3. 非目标

- 不恢复被整段删除的剧情（正确做法是换源/补源，不是让模型编）；
- 不做整章改写、文风润色、续写；
- 不在服务端长期保存整本正文；
- 不在高峰时段调用；
- 不把 DeepSeek API Key 放进 APK；
- 不自动替用户换源（检测到拼接只提示）。

## 4. 已定决策

| 决策点 | 结论 |
|---|---|
| 流水线位置 | 服务端批量流水线（`backend/apps/polish`） |
| 修复尺度 | 允许补缺失字句，但只做逐处最小补丁，低置信不动 |
| 处理范围 | 在读的书优先，整本排队 |
| 时段与费用 | 只在空闲时段跑，设每日/单本预算上限 |
| 服务端存储 | 只存补丁、判定结果、统计与限时限量的对齐样本；原文处理完即删 |
| 补丁生效方式 | 客户端直接改写章节缓存文件（首次改写保留 `.orig` 备份） |
| 拼接处理 | 跳过润色 + 阅读页提示 + 建议换源，不改写、不自动换源 |
| 源级规则范围 | 全局共享，人工审核后启用 |
| 源特征学习数据 | 多用户多源对齐 + 单源统计 + LLM 样本分析 |
| 源级规则生效 | 客户端本地确定性应用；规则版本参与 `source_hash` |

## 5. 问题分类与处理

| 类型 | 例子 | 处理 | 成本 |
|---|---|---|---|
| A 广告/网址 | `www.xxx.com`、公众号、QQ 群、求票 | 确定性规则 + LLM 补丁 | 低 |
| B 字符噪声 | 零宽字符、乱码、全半角、被空格拆字 | 确定性规则优先，LLM 兜底 | 低 |
| C 随机缺字/错字 | 个别章节少一个字、同音替换、句子断裂 | LLM 逐处补丁 | 低 |
| D 整段缺失 | 段落直接没有 | `unrecoverable`，不补，提示换源 | 0 |
| E 源级系统性缺字/换字 | 全源统一删「得/花」、把「干」换成「乾」、反义替换 | 源级档案 + 确定性规则；LLM 只用于学习与残余 | 一次学习后接近 0 |
| F 付费残缺+拼接 | 前几段正文后接别的小说 | `contaminated`，整章放弃润色 | 1 次调用 |

A/B 两类的确定性规则在客户端和服务端都会跑一遍：客户端用现有替换规则/本地正则先清一遍，服务端只处理仍然可疑的文本。

E 类（源级系统性）不能按章丢给 LLM：同一源的所有章节都遵守同一套删字/换字规则，逐章猜测既贵又不一致，反向替换还会误伤正常文本。正确做法是学习一份**源级归一化档案**（见第 11 节），在所有章节上确定性应用；LLM 只参与“从样本里归纳规则”和“残余上下文修补”。

## 6. 架构

```
手机 App（正文缓存）                     后端 118.31.79.174
┌──────────────────┐   上传章节批次     ┌────────────────────────────┐
│ 已缓存章节        │ ─────────────────▶ │ POST /api/legado/app/       │
│ raw md5 / 优先级  │                    │      polish/jobs            │
└──────────────────┘                    │  入队 PolishJob             │
        ▲                               │          │                  │
        │ 增量拉取补丁                   │  APScheduler 每 5 分钟       │
        │                               │  空闲窗口 worker ×4         │
┌──────────────────┐                    │          ▼                  │
│ Room 补丁表       │ ◀───────────────── │  DeepSeek flash             │
│ 改写缓存 / 提示   │  GET /patches      │          │                  │
└──────────────────┘                    │  本地预筛 → 校验 → 落库      │
                                        │  原文处理完置空              │
                                        └────────────────────────────┘
```

组件职责：

- **客户端**：判定哪些章节可上传、上传原文、拉取结果、改写缓存、提示拼接章节、回退原文。
- **服务端 API**：鉴权（`X-User-Phone`）、入队、返回补丁、返回状态。
- **服务端 worker**：空闲窗口调度、预算控制、调用 DeepSeek、校验、去重、熔断。
- **DeepSeek**：只输出“完整性判定 + 逐处补丁”，不做整章生成。
- **源级档案**：同一书源学一次（多源对齐 + 字频 + LLM 归纳），客户端渲染/改写时确定性套用；不参与逐章计费。

## 7. 内容完整性甄别（付费残缺 + 拼接）

### 7.1 本地预筛（免费）

在调用 LLM 前先扫描，结果作为 `local_flags` 一起交给模型：

| 信号 | 规则 |
|---|---|
| 付费提示 | 正文前 85% 出现 `本章未完`、`请充值`、`VIP章节`、`订阅本章`、`购买本章`、`金币不足`、`余额不足`、`充值后阅读`、`试读` 等 |
| 中途章节标题 | 正文 10% 之后、90% 之前出现第二行 `^第[一二三四五六七八九十百千万零〇\d]+章` 或 `^Chapter\s+\d+` |
| 长度异常 | 本章长度 > 全书已缓存章节中位数的 3 倍，且命中上一条 |
| 重复段落 | 后 50% 与前面存在连续 200 字以上的重复片段 |

预筛只产生“可疑信号”，不单独判死刑。

### 7.2 LLM 判定

与润色同一次调用，模型必须先给出完整性结论：

```json
{
  "verdict": "ok | contaminated | suspicious",
  "boundary": 12,
  "reason": "第13段起人物、地点、文风全部更换，后文自成一个故事开头",
  "confidence": 0.92,
  "edits": [],
  "unrecoverable": []
}
```

判定口径写进 system prompt：

- `contaminated`：叙事断裂，后文出现另一本书的人物/地点/时代/主线，或出现另一个章节标题；前文在付费提示处截断。
- `ok`：正常的场景切换、回忆、多线叙事、视角切换，不算拼接。
- `suspicious`：判断不确定，或只有弱信号。
- **判定为 `contaminated` / `suspicious` 时，`edits` 必须为空；不得为了“修顺”而改写拼接内容。**

### 7.3 冲突裁决

服务端按以下顺序裁决，宁可少改不可错改：

1. LLM `contaminated` 且 `confidence >= 0.7` → `contaminated`；
2. 本地强信号（中途章节标题 / 付费提示）且 LLM 不是 `contaminated` → 降级为 `suspicious`，不落地补丁；
3. LLM `suspicious` → `suspicious`；
4. `confidence < 0.7` 的 `ok` → 改为 `suspicious`，不落地补丁；
5. 只有 `ok` 且 `confidence >= 0.7` 才进入补丁校验。

### 7.4 书级熔断

- 同一本书连续 3 章 `contaminated` → `PolishBookState.status = paid_truncated`；
- 该书所有 `queued` 任务改为 `skipped`，客户端停止为该书上传播；
- 客户端在书籍详情提示“该源付费残缺，建议换源”，并保留手动重新启用入口。

### 7.5 客户端处理

用户已确认：**跳过 + 提示**。

- 不改写缓存文件；
- 阅读页首次进入该章时提示：“本章疑似付费残缺/拼接，未做润色，建议换源”；
- 书籍详情展示拼接章节列表（章名、边界段号、理由）；
- 不自动触发换源；用户可手动使用现有换源/补源功能；
- 判定结果缓存，不重复上传同一 `contentHash`。

## 8. 补丁契约

### 8.1 响应

```json
{
  "verdict": "ok",
  "boundary": null,
  "reason": "",
  "confidence": 0.93,
  "edits": [
    {
      "p": 12,
      "from": "他走进了屋坐了下来",
      "to": "他走进了屋子，坐了下来",
      "type": "missing",
      "conf": 0.86
    },
    {
      "p": 31,
      "from": "最新章节请访问 www.example.com",
      "to": "",
      "type": "ad",
      "conf": 0.99
    }
  ],
  "unrecoverable": [
    {"p": 20, "reason": "整段缺失"}
  ]
}
```

字段：

| 字段 | 说明 |
|---|---|
| `verdict` | `ok` / `contaminated` / `suspicious` |
| `boundary` | 拼接起点段号（0 基），仅 `contaminated` 时有值 |
| `reason` | 判定理由，给用户看，≤ 200 字 |
| `confidence` | 0~1 |
| `edits[].p` | 段号（0 基，按 `\n` 分段） |
| `edits[].from` | 原文中**该段唯一出现**的子串 |
| `edits[].to` | 替换文本；空串表示删除 |
| `edits[].type` | `ad` / `noise` / `typo` / `missing` / `other` |
| `edits[].conf` | 单处置信度 |
| `unrecoverable` | 整段缺失，不回填 |

### 8.2 服务端校验

任一失败整章不落地，原文不动：

| 校验 | 规则 |
|---|---|
| schema | 必须是 JSON 对象，`verdict` 合法 |
| 段号 | `0 <= p < 段落数` |
| 锚点 | `from` 非空，且在该段出现次数恰好为 1 |
| 无操作 | `to == from` 的编辑丢弃 |
| 单处长度 | `len(to) - len(from) <= 30` 字（可配） |
| 总修改量 | 所有编辑的净变化绝对值和 / 全文字数 <= 5%（可配）；纯广告/网址删除不计入 |
| 新增网址 | `to` 出现 URL/域名/联系方式而 `from` 没有 → 拒绝 |
| 编辑数量 | 单章最多 40 处 |
| 拼接文本 | `verdict != ok` 时忽略所有 edits |
| 边界 | `contaminated` 时 `boundary` 必须在 1..段落数-1 |

校验通过后输出带绝对偏移的补丁：

```json
{"edits":[{"p":12,"start":430,"end":440,"from":"他走进了屋坐了下来","replace":"他走进了屋子，坐了下来"}]}
```

`start/end` 是相对**整章原文**的字符偏移；`from` 保留给客户端做落地前校验（切出来的原文必须与 `from` 完全一致）。

### 8.3 幂等

- 主键：`(chapter_url, source_hash)`；同一原文永远得到同一份补丁；
- 原文变化（重新抓取/换源）→ `source_hash` 变化 → 旧补丁不再命中，重新排队；
- 客户端应用前必须校验 `md5(当前缓存正文) == source_hash`，否则丢弃。

## 9. 服务端设计

### 9.1 应用与模型

新增 Django 应用 `apps/polish`，注册到 `INSTALLED_APPS`。

**PolishJob**（每用户每章一条）

| 字段 | 说明 |
|---|---|
| user | FK `legado.LegadoUser` |
| book_url / book_name / source_url | 书籍与书源 |
| chapter_url / chapter_index / chapter_title | 章节 |
| source_hash | 原文 md5（含源级档案版本的归一化文本 hash） |
| profile_version | 源级档案版本，参与 hash 与审计 |
| raw_text | 原文，处理完置空；失败保留 7 天 |
| status | queued / processing / done / contaminated / suspicious / rejected / failed / skipped |
| priority | 0 = 在读，100 = 其他 |
| attempts | 重试次数 |
| verdict / verdict_reason / boundary_paragraph | 完整性判定 |
| patch_json | 校验后的补丁 |
| tokens_in / tokens_out / cost_micros / model | 计费 |
| error | 失败原因 |

唯一约束：`(user, book_url, chapter_index, source_hash)`；索引：`(status, priority, create_time)`。

**PolishPatchCache**（全局去重）

`(chapter_url, source_hash)` 唯一；存 `verdict / patch_json / boundary / reason / model`。
入队时若已存在缓存，直接生成一条 `done` 的 job，0 费用。

**PolishQuota**（用户日预算）

`(user, date)` 唯一；`spent_micros / job_count`。

**PolishBookState**（书级熔断）

`(user, book_url)` 唯一；`status(normal/paid_truncated/paused)`、`contaminated_streak`、`last_reason`。

### 9.2 API

统一放在 `/api/legado/app/polish/`，鉴权复用 `X-User-Phone`（`apps/legado/views_app.get_user`）。

**POST /jobs**

```json
{
  "bookUrl": "...",
  "bookName": "...",
  "sourceUrl": "...",
  "priority": 0,
  "chapters": [
    {"index": 12, "url": "...", "title": "...", "hash": "md5", "text": "..."}
  ]
}
```

限制：`chapters <= 20`，单章 `text <= 12000` 字，单请求 `text` 总量 <= 200KB。
返回：`{"accepted": 20, "deduped": 3, "skipped": 0}`。

**GET /patches?bookUrl=&since=&limit=50**

返回该书的补丁与判定结果；`since` 用 `update_time` 毫秒时间戳做增量。成功生成的补丁 `status=done`、`verdict=ok`，客户端统一映射为 `ok`；`contaminated/suspicious` 原样返回。

**GET /status?bookUrl=**

返回该书 `queued/processing/done/contaminated/suspicious/failed` 计数、今日花费、书级状态。

### 9.3 队列与优先级

- 优先级：在读的书 `priority=0`，其他 `priority=100`；
- 同优先级按 `create_time` 升序；
- 用 `select_for_update(skip_locked=True)` 领取（MySQL）；SQLite 本地开发退化为“先查再改”；
- 进程崩溃的 `processing` 超过 10 分钟由下一次任务回收为 `queued`。

### 9.4 空闲窗口

北京时间：

- 高峰：周一至周五 `09:00-12:00`、`14:00-18:00`；
- 空闲：其余时间 + 周末 + 法定节假日全天；
- 节假日由每月定时任务从 holiday-cn 年度 JSON 抓取（`isOffDay=true`），校验后写入 `SystemConfig.polish_holidays`；抓取失败重试 3 次并保留旧值；`is_idle_window` 读库并缓存 5 分钟，`POLISH_HOLIDAYS` 只作兜底。
- `seed_polish_holiday_job` 注册 `0 30 3 1 * *`（每月 1 号 03:30）；数据源可用 `POLISH_HOLIDAY_URL` / `LEGADO_POLISH_HOLIDAY_URL` 覆盖。

worker 每次取任务前检查窗口；进入高峰立即停止取新任务，正在处理的任务允许完成。

### 9.5 预算

- 每日预算：`SystemConfig` 键 `polish_daily_budget`（元），缺省取 `POLISH_DAILY_BUDGET_CNY=2`；
- 单本预算：`POLISH_BOOK_BUDGET_CNY`，默认不设；
- 每章调用前按 `字数 / 1.6` 估算输入 token，超过剩余预算不取任务；
- 调用后按 `usage` 落 `cost_micros`（元 × 1,000,000）：
  - flash 空闲：输入命中 ¥0.02/M、未命中 ¥1/M、输出 ¥4/M；
  - 高峰价格 ×2；
  - `deepseek-v4-pro` 用另一组价格，默认不用。

### 9.6 去重

- 全局补丁缓存按 `(chapter_url, source_hash)`；
- 同一本书同一章多个用户上传，只有第一个用户产生费用；
- 客户端上传前查本地已应用补丁，避免重复上传。

### 9.7 重试与幂等

- HTTP 429/5xx/超时：指数退避重试 2 次；
- JSON 解析失败/校验失败：`rejected`，不自动重试（同输入同结果，重试浪费钱）；
- `contaminated` / `suspicious`：不重试；
- 所有结果按 `(chapter_url, source_hash)` 幂等写入。

### 9.8 安全

- API Key 只放服务端环境变量 `LEGADO_DEEPSEEK_API_KEY`；
- 日志只记 hash、字数、token、费用，不记正文；
- 单用户上传频率限制：每分钟最多 6 批，每天最多 2000 章；
- 原文在 `done/contaminated/suspicious` 后置空；失败保留 7 天供排查；
- 源级对齐样本单独限时限量：每源最多 20 章、每章 4000 字、TTL 30 天，不写日志。

## 10. 客户端设计

### 10.1 设置

新增偏好：

| key | 默认 | 说明 |
|---|---|---|
| `aiPolishEnabled` | false | 总开关 |
| `aiPolishWifiOnly` | true | 仅 WiFi 上传/拉取 |
| `aiPolishKeepOriginal` | true | 改写前保留 `.orig` |

`aiPolishLastSync` 加入设置同步排除表。

### 10.2 上传

- 只处理文本在线书（`Book.type` 含 `BookType.text` 位，且不含 `local/audio/image/video` 位，`origin != loc_book`）；
- 上传**原文/`.orig`**，不上传已改写文本，避免源级规则被重复套用；
- 跳过卷名、`isContentReversed` 的章节、正文为空；
- 已有补丁且 `sourceHash` 相同的章节跳过；
- 每批 10 章，单章 ≤ 12000 字；
- 触发：书籍详情“立即清洗”、`SAVE_CONTENT` 事件（缓存完成）去抖 30 秒。

### 10.3 拉取

- 按书 `since` 增量拉取，写入 `aiChapterPatches`；
- 打开书籍详情、阅读页 `onResume` 时拉一次；
- 仅 WiFi（可关）。

### 10.4 改写缓存

新增 `AiChapterPatch` 表：

```
id, bookUrl, chapterIndex, chapterUrl, sourceHash, appliedHash, appliedTextHash,
status(ok/contaminated/suspicious), patchJson, boundaryParagraph,
reason, appliedProfileVersion, restored, appliedAt, updatedAt
```

改写流程：

1. 以 `.orig` 为基底（没有则用当前缓存），先应用当前源级档案得到 `normalized`；
2. `sourceHash = md5(profileVersion + "\n" + normalized)`，与补丁行比对：
   - 相等且当前缓存 md5 != `appliedTextHash` → 应用补丁；
   - 当前缓存 md5 == `appliedTextHash` → 已改写，跳过；
   - 都不等 → 源文或档案已变，丢弃本次补丁，重新排队；
3. 应用补丁（全有或全无，`from` 必须与原文一致，任一锚点不命中整章放弃）；
4. 改写前保留 `<章节文件>.orig`（已存在则不覆盖）；
5. 用 `BookHelp.saveText(book, chapter, cleaned)` 改写（复用现有 `contentSaveFence`，不写源信息、不更新章节元数据）；
6. 更新 `appliedHash`（normalized 的 hash）、`appliedTextHash`（最终文本 md5）、`appliedProfileVersion`、`appliedAt`；
7. 位置修正见 10.5。

跳过条件：

- 该书当前正在阅读的章节（`ReadBook.durChapterIndex`）不落地，等离开该书或切到其他章后再改；
- 朗读会话正在读的该书当前章也不落地，避免 TTS 读到一半文本变化；
- `restored=true` 的章节不自动重新应用；
- `isContentReversed` 为 true；
- 源站重新抓取/换源成功后删除该章 `.orig`，确保新原文成为基底，避免用旧补丁覆盖新内容；
- 章节文件不存在。

### 10.5 进度 / 书签 / 高亮位置

补丁带 `start/end/from/replace` 绝对偏移。改写后：

- 如果当前书进度指向**非当前打开章节**，按编辑列表把 `Book.durChapterPos` 映射到新文本；
- 书签/高亮按同一映射修正 `chapterPos / chapterPosEnd`；
- 映射失败（位置落在被删除区间且无法裁剪）→ 保留旧值并记日志，不阻塞改写；
- 当前打开章节不改写，因此在读位置不会跳字。

### 10.6 拼接章节提示

- 拉取到 `status=contaminated` / `suspicious` 时只入库，不改缓存；
- 阅读页进入该章时提示一次：“本章疑似付费残缺/拼接，未做润色，建议换源”；
- 书籍详情“AI 清洗”面板列出疑似章节、边界段号、理由；
- 用户可一键“还原原文”（恢复 `.orig`，并把该章标记 `restored=true`，不改服务端数据）。

### 10.7 回退

- 单章还原：`.orig` 存在则写回，把补丁行标记 `restored=true` 并清空 `appliedAt/appliedHash`；
- 整本还原：逐章恢复 `.orig`，全部标记 `restored=true`；
- `restored` 的章节在后续同步中不会被自动重新应用；用户点“立即清洗”时先清除 `restored` 再上传；
- 服务端永远保留 `source_hash` 与补丁，客户端可重新拉取；已还原的章节需要用户手动触发才会再次清洗。

## 11. 源级特征库（系统性缺字 / 换字）

### 11.1 为什么不能逐章用 LLM

源级损坏是**全源统一规则**：某站所有书的「得」「花」都被删、所有「干」都变「乾」、所有反义对都被调换。逐章让模型猜有三个硬伤：

- 同一规则在不同章节被修得不一样，阅读口径不一致；
- 每章都要付一次调用费，几十万章的量级不可接受；
- 反义替换是语义级的，单章模型容易把正常文本也“修反”，误伤面大。

因此 E 类拆成两层：

1. **源级档案**：一次学习，确定性应用到该源的所有书、所有章；
2. **逐章 LLM**：只修档案覆盖不到的残余上下文问题。

### 11.2 档案结构

`PolishSourceProfile`（按 `source_url` 全局唯一）：

```json
{
  "version": 3,
  "status": "verified",
  "confidence": 0.96,
  "missing_chars": ["得", "花"],
  "char_map": {
    "乾": {"to": "干", "exceptions": ["乾坤", "乾隆", "乾卦", "乾陵"]}
  },
  "context_rules": [
    {
      "pattern": "([\\u4e00-\\u9fa5]{1,3})(很|非常|太|十分|特别)([\\u4e00-\\u9fa5])",
      "replacement": "$1得$2$3",
      "examples": ["跑很快→跑得很快", "说很好→说得很好"],
      "confidence": 0.91
    }
  ],
  "antonym_pairs": [
    {"from": "大", "to": "小", "direction": "a2b", "confidence": 0.95}
  ],
  "evidence": {
    "aligned_chapters": 18,
    "aligned_sources": 2,
    "holdout_false_change_rate": 0.004
  }
}
```

- `missing_chars` 只表示“该源系统性删除”，真正插入靠 `context_rules`，不允许无上下文盲插；
- `char_map` 必须带例外白名单（「乾坤」「乾隆」不能改）；
- `antonym_pairs` 必须有方向和置信度，只有对齐证据足够时才启用；
- 每条规则都要有例子和置信度，后台可单条禁用。

### 11.3 学习流程

1. **采样**：每源最多保留 20 章对齐样本（`PolishAlignSample`），每章截取前 4000 字，TTL 30 天；优先保留“同一章在多个源都有”的样本。
2. **多源对齐**（主证据）：按 `book_key(书名+作者)` + `chapter_index` 配对，index 对不齐时退化为规范化标题匹配；把目标源的样本与其他源的同章样本配对，用 `difflib.SequenceMatcher` 做段级 + 字符级 diff，统计 `replace/delete/insert`。不同源的 `bookUrl`/`chapterUrl` 不同，绝不能按 URL 配对。
3. **单源统计**（辅助证据）：对 20 万字以上的样本做常用字频率检验，找出“应有却为 0”的字（如「得」）和“异常高频”的异体字（如「乾」）。
4. **一致性聚合**：
   - 普通换字：出现在 ≥ 80% 的对齐对、且 ≥ 3 个不同章节；
   - 反义对：出现在 ≥ 90% 的对齐对、且 ≥ 3 个不同章节；
   - 达不到阈值的只进 `suspicious`，不生成规则。
5. **LLM 归纳**：把统计候选 + 3~5 段短样本交给 DeepSeek，要求只输出最小规则集与白名单，不允许自由改写；模型给的规则必须能对应到统计候选。
6. **留出集验证**：保留 20% 样本不参与学习，应用候选档案后检查：
   - `changed_ratio`（改动字符占比）≤ 5%；
   - 误改率 < 1%（人工抽查 50 处）；
   - 目标问题覆盖率提升 ≥ 30%。
7. **人工审核**：`draft → verified` 后才下发给客户端；`disabled` 可即时回滚。

### 11.4 应用与版本

- 客户端拿到 `verified` 档案后，在**上传前**和**改写缓存前**都对原文应用同一份档案；
- `sourceHash = md5(profileVersion + "\n" + normalizedText)`，档案版本参与 hash，避免旧补丁套到新规则上；
- 服务端 `PolishJob` / `PolishPatchCache` 记录 `profile_version`，便于审计；
- **有逐章补丁的章节**：改写缓存时以 `.orig` 原始文本为基底，先应用档案、再应用补丁，缓存即最终文本；渲染时不再套规则；
- **没有补丁的章节**：缓存保持原文，由 `ContentProcessor` 在渲染时套用档案（免费、即时，不必为了源级规则重写整本缓存）；
- 用补丁行的 `appliedAt` 区分两种状态，避免“缓存已含规则、渲染又套一遍”的双重应用；
- 档案升级或回滚时，客户端从 `.orig` 重新生成有补丁的章节；无补丁章节在渲染时自动使用新版本；
- 每本书可单独关闭“源级规则”，关闭后恢复 `.orig`，逐章补丁仍可保留。

### 11.5 成本

- 每源一次性：20 章 × 4000 字 ≈ 5 万 tokens，LLM 归纳一次 < ¥0.1；对齐在服务端本地完成，不额外调用；
- 档案生效后，该源所有章节的缺字/换字修复**不再逐章付费**；
- 没有档案的源仍走逐章补丁，成本按第 12 节计算。

### 11.6 风险与对策

| 风险 | 对策 |
|---|---|
| 错误规则污染全源 | `draft/verified/disabled` 三态；留出集验证；人工审核；单条规则可禁用 |
| 反义方向学反 | 只在高置信对齐（≥90%、≥3 章）时生成；先 `suspicious` 不自动应用 |
| 「乾坤/乾隆」被误改 | 例外白名单；命中白名单的片段不参与替换 |
| 盲插「得/花」造成新语病 | 只允许带上下文正则的规则；无规则时交给逐章 LLM 或标 `unrecoverable` |
| 档案升级导致补丁失效 | 版本参与 hash；客户端从 `.orig` 重新应用；旧版本补丁保留审计 |
| 样本版权/存储 | 每源最多 20 章、每章 4000 字、TTL 30 天；只用于对齐统计 |



## 12. 成本

DeepSeek 官方现价（2026-11 核实）：

| 模型 | 时段 | 输入命中 | 输入未命中 | 输出 |
|---|---|---|---|---|
| deepseek-flash | 空闲 | ¥0.02/M | ¥1/M | ¥4/M |
| deepseek-flash | 高峰 | ¥0.04/M | ¥2/M | ¥8/M |
| deepseek-v4-pro | 空闲 | ¥0.15/M | ¥4.5/M | ¥13.5/M |

估算（100 万汉字 ≈ 0.625M tokens）：

| 方式 | flash 空闲 | flash 高峰 |
|---|---|---|
| 只出补丁（约 3% 改写） | ≈ ¥0.7–1.0 | ≈ ¥1.4–2.0 |
| 整章重写（不采用） | ≈ ¥3.1 | ≈ ¥6.2 |

- 一本 300 万字：¥2–3；
- 每日预算 ¥2：约 1 本/天，周末可翻倍；
- 拼接判定不额外收费（同一次调用）；
- 全局去重后，热门书源的实际边际成本更低；
- 源级档案每源一次性 < ¥0.1，生效后该源的缺字/换字修复不再逐章付费。

## 13. 验收

必须同时满足：

1. 广告/网址/水印残留为 0（20 章人工抽查）；
2. 误改率 < 1%（人工对照原文，统计非广告类修改）；
3. 拼接章节 100% 不落地补丁，且给出可读理由；
4. 原文可回退：单章/整本还原后内容与改写前一致；
5. 进度不倒退：改写后重新打开章节，阅读位置误差 ≤ 1 段；
6. 时段门控有日志证据：高峰时段无新任务被领取；
7. 预算门控有日志证据：达到日预算后停止取任务；
8. 100 万字实际费用 ≤ ¥2（空闲时段）；
9. 服务端 `raw_text` 在成功/判定后为空；
10. 源级档案：同一源的「得/花」缺失与「干→乾」换字在留出集上的修复率 ≥ 80%，误改率 < 1%；
11. 源级档案回滚后，客户端能从 `.orig` 恢复原文，且不会二次叠加旧规则。

### 13.1 真实冒烟记录（2026-11-18）

- 模型：`deepseek-flash`；`polish_smoke --force`（当时为高峰时段，费用按高峰计价）。
- 输入：`clean_ok` / `ad_and_missing` / `spliced` 三个合成章节。
- 结果：
  - `clean_ok`：`done`，0 edits，¥0.001762；
  - `ad_and_missing`：`done`，删除广告行，`走进了屋→走进了屋子`，`他跑很快→他跑得很快`，¥0.003156；
  - `spliced`：`contaminated`，`boundary=3`，0 edits，¥0.002616；
  - 合计：¥0.007534 / 3 章。
- 源级档案（`src-a` vs `src-b`，5 对合成样本）：`draft v1 rate=0.0`；`char_map` `乾→干`（含「乾坤/乾隆」例外），`missing_chars=["得"]`，`context_rules` 学到保守的字面规则「跑很快→跑得很快」；审核后 `/source-profile` 返回 200，应用结果为「他跑得很快。她说很好。他站在干净的屋子。」
- 结论：广告删除、缺字补丁、拼接判定与边界、源级换字/缺字标记均真实跑通；反义对需要更丰富的真实样本，字面 context rule 需要在审核时改成可泛化正则或交给逐章 LLM。
- 节假日刷新：真实抓取 2026+2027 共 62 天；`2026-10-01 10:00` idle=True，`2026-11-18 10:00` peak，`2027-10-01 10:00` idle=True。

## 14. 风险与对策

| 风险 | 对策 |
|---|---|
| 模型幻觉补写剧情 | 只允许逐处补丁；`from` 必须唯一命中；总修改量 ≤5%；整段缺失只标 `unrecoverable` |
| 拼接误判为正常 / 正常误判为拼接 | 本地信号与 LLM 双判；低置信一律 `suspicious` 不落地；只提示不自动换源 |
| 改写导致进度/书签漂移 | 不在当前打开章节改写；按偏移映射修正；映射失败保留旧值 |
| 源文更新导致补丁失效 | `source_hash` 校验；不匹配丢弃并重排 |
| 服务端存正文的版权风险 | 只存补丁；原文处理完置空 |
| 高峰时段烧钱 | 空闲窗口硬门控 + 日预算 + 单章估算前置 |
| 客户端反复上传 | 本地补丁表去重 + 服务端 `(chapter_url, source_hash)` 唯一 |
| JSON 输出不稳定 | `response_format=json_object` + 代码块剥离 + 整章回退 |

## 15. 分期

- **P0 后端**：应用/模型/API/队列/空闲调度/预算/DeepSeek/校验/拼接判定/缓存/清理；用 curl 与真实 API 跑通。
- **P1 客户端**：Room 补丁表、上传器、拉取、改写缓存、位置修正、设置与提示、还原。
- **P2 源级特征库**：对齐样本、多源对齐、LLM 归纳、留出集验证、全局档案下发、客户端应用与回滚。
- **P3**：后台 diff 报表、书签/高亮精确映射、自动换源候选、按书质量评分。

## 16. 明确不做

- 服务端长期保存全文；
- 整章改写/文风润色/续写；
- 模型编造缺失剧情；
- 高峰时段调用；
- API Key 下发到客户端；
- 检测到拼接后自动替用户换源。
