# MealMind 原型 · Design Contract（设计契约）

2026-10-01 · v0.1 · 本文件是原型所有页面的唯一视觉与结构依据，所有页面必须逐字沿用其中的「App Shell」与导航结构。

## 1. 定位与风格

- **意图**：reference —— 依据淘宝闪购参考截图（`docs/ui-reference/`）还原设计语言，生成 MealMind 高保真可交互原型。
- **技术栈**：纯静态 `HTML + CSS + 原生 JS`（无构建、无 CDN 依赖，file:// 可直接打开）；目录 `prototype/`。
- **style tier**：brand-themed（本地生活电商暖色系）。
- **aesthetic**：温暖活力的本地生活信息流 —— 高信息密度、圆角卡片、红橙主色、强 CTA；AI 模块用同色系渐变与光感做身份区分。
- **tone**：热闹、可信、克制；动效只出现在加载入场与关键反馈。

## 2. 参考映射

| 参考文件                                                                                                      | 对应页面                 | 用途                                               |
| ------------------------------------------------------------------------------------------------------------- | ------------------------ | -------------------------------------------------- |
| `home.png` / `home-merchant-list.png` / `home-merchant-list-skeleton.png`                                     | `home.html` 闪购主页     | 结构、分类两排、商家卡、骨架屏                     |
| `foods-home.jpg`                                                                                              | `foods.html` 美食外卖页  | 红包区、品类 chips、商家卡（含菜品预览）           |
| `merchant-home.png` / `merchant-footlist.png` / `specs-layer.png` / `cart-in-merchant.png` / `cart-after.png` | `merchant.html` 商家详情 | 头部、菜单双栏、规格弹层、购物车面板、底部购物车条 |
| `cart.png`                                                                                                    | `cart.html` 购物车页     | 商家分组、条目、底部结算栏                         |
| `animation.mp4`                                                                                               | 全局                     | 加购飞入购物车动效（入场帧已核对）                 |

## 3. Design Tokens

```
color.primary      #FF5000      color.primary-deep  #E64600
color.brand-red    #FA2C19      color.pink-tint     #FFF1EC
color.bg           #F4F5F7      color.surface       #FFFFFF
color.border       rgba(17,17,26,.07)
color.text         #1B1B1F      color.text-sub      #6B6F76   color.text-tertiary #9EA2A8
color.success      #21A45D      color.warning       #FF8800   color.danger #FA2C19
grad.cta           linear-gradient(90deg,#FF3B1F 0%,#FF7A00 100%)
grad.ai            linear-gradient(135deg,#FF7A00 0%,#FF3D6E 100%)
font.body          -apple-system,"PingFang SC","Helvetica Neue",sans-serif
font.price         "DIN Alternate","Bahnschrift",-apple-system,sans-serif
font.scale         11 / 12 / 13 / 14 / 16 / 18 / 20 / 22（无 13px 以外散值）
radius             sm8 / md12 / lg16 / pill999
shadow.card        0 2px 10px rgba(17,17,26,.05)
shadow.float       0 6px 20px rgba(255,80,0,.28)
spacing.unit       4px（4/8/12/16/20/24/32）
layout             .app 最大宽 414px 居中；内容左右留白 12/16
icon.lib           Lucide（内联 SVG，stroke 1.8，currentColor）—— 严禁 emoji 当图标
bg-texture         页面头部暖色渐变雾面（radial + linear），其余中性底
motion             入场：.stagger 子级 fadeUp 阶梯（30~240ms，位移 8px）；反馈动效克制
```

字体说明：产品为中文电商高密度界面，为贴近参考与可用性采用系统中文栈；价格数字使用 DIN 系数字体增强识别（这是对 anti-slop 字体配对的刻意豁免，理由记录在此）。

## 4. 组件规范（styles.css 已实现）

| 组件       | 类名                                                                    | 说明                                                              |
| ---------- | ----------------------------------------------------------------------- | ----------------------------------------------------------------- |
| 按钮       | `.btn` `.btn-lg` `.btn-grad` `.btn-outline` `.btn-disabled`             | 渐变主 CTA / 描边次按钮；高度 28/36/48                            |
| 标签       | `.tag`（`.tag-red` `.tag-orange` `.tag-green` `.tag-gray` `.tag-line`） | 11px，radius 4，营销标签红、特色标签灰                            |
| 卡片       | `.card` `.sec-head`                                                     | 圆角 12、白底、轻阴影                                             |
| 商家卡     | `.mcard` `.mcard--dishes` `.mcard-badge`                                | 88px 方图 + 名称/评分/标签；`--dishes` 含菜品预览条 `.disc-strip` |
| 骨架屏     | `.sk` `.sk-line` `.sk-img` `.sk-card`                                   | shimmer 动画，用于商家列表加载                                    |
| 搜索       | `.searchbar`                                                            | 白底、1px 橙描边胶囊、内嵌渐变「搜索」按钮                        |
| 分类宫格   | `.cat-grid` `.cat` `.cat-tile`                                          | 2×5，44px 色块 + Lucide 图标 + 11px 文案                          |
| 筛选 chips | `.chip` `.chip.on`                                                      | 胶囊，选中态浅橙底橙字                                            |
| 推广条     | `.promo-strip` `.promo-cell`                                            | 满减/15 分钟/免配送费等小标签行                                   |
| 底部导航   | `.tabbar` `.tab` `.tab-ai`                                              | 五项，AI 居中凸起渐变；角标 `.tab-badge`                          |
| 状态栏     | `.statusbar`                                                            | 仿真状态栏（时间+信号/WiFi/电量），随 Shell 复制                  |
| Toast      | `.toast`                                                                | 深色胶囊，底部居中，`showToast()` 触发                            |
| 底部弹层   | `.sheet` `.sheet-mask`                                                  | 规格选择层，顶部圆角 16                                           |
| 步进器     | `.stepper`                                                              | 减号白底红描边、加号红底白字                                      |
| 分段       | `.seg`                                                                  | 外送/自取                                                         |
| 红包券     | `.voucher`                                                              | 粉色票券卡（美食外卖页）                                          |
| 空态       | `.empty`                                                                | 图标 + 文案 + 行动按钮                                            |

## 5. App Shell（多页一致性关键，逐字复制，不得改写）

三种变体，页面开头 `<body data-page="...">` 是唯一差异：

- **变体 A（标准）**：状态栏 + `.screen` 内容 + 底部五项 Tab。用于 `home / foods / cart / mine / eval`。
- **变体 B（商家）**：状态栏 + `.screen` + 底部购物车条（`merchant.html` 专用，无 Tab）。
- **变体 C（沉浸）**：状态栏 + `.screen`。用于 `index`（封面）与 `agent`（AI 会话）。

冻结导航（变体 A，顺序/文案/图标/链接不得增删改）：

| #   | 文案      | data-nav | 链接                              | 图标                                      |
| --- | --------- | -------- | --------------------------------- | ----------------------------------------- |
| 1   | 推荐      | home     | `home.html`                       | house                                     |
| 2   | 消息      | —        | 占位（`.js-placeholder` → Toast） | message-circle                            |
| 3   | AI 点外卖 | agent    | `agent.html`                      | sparkles（居中凸起）                      |
| 4   | 购物车    | cart     | `cart.html`                       | shopping-cart（带角标 `data-cart-badge`） |
| 5   | 我的      | mine     | `mine.html`                       | user                                      |

激活规则（唯一机制）：`app.js` 读取 `document.body.dataset.page`，为 `.tabbar [data-nav]` 中匹配项添加 `.is-active`；页面不得手动指定激活项。

定位规则：`.tabbar` 固定底部、`.app` 内水平居中（max-width 414）；`.screen` 预留 `padding-bottom: calc(64px + env(safe-area-inset-bottom))`，任何页面不得改动。

层级约定（固定，不得随意调整）：`.tabbar` z-index 70 > 页面底部操作条 z-index 60 > 会话输入条 z-index 40。中间「AI 点外卖」按钮向上凸出 17px，必须高于底部操作条，否则会被遮挡。

## 6. 页面清单

| 页面        | 文件            | 职责                                                                         | 去向                       |
| ----------- | --------------- | ---------------------------------------------------------------------------- | -------------------------- |
| 封面        | `index.html`    | 品牌封面 + 游客登录（登录态长期保持，已登录直达首页；`?preview` 可停在封面） | → home                     |
| 闪购主页    | `home.html`     | 定位/搜索、2×5 分类（仅「美食外卖」可点）、筛选、推荐商家流（骨架屏）        | → foods / merchant / agent |
| 美食外卖页  | `foods.html`    | 红包券、品类 chips、排序条、商家列表（含菜品预览）                           | → merchant                 |
| 商家详情    | `merchant.html` | 头部信息、菜单双栏、规格弹层、购物车面板、底部购物车条                       | → cart / agent             |
| 购物车      | `cart.html`     | 按商家分组、条目管理、合计（结算=Toast 说明）                                | → merchant                 |
| AI 决策会话 | `agent.html`    | 需求输入、快捷标签、进度、评分卡片、加购                                     | → cart / merchant          |
| 我的        | `mine.html`     | 身份、偏好记忆卡、评测看板入口、退出登录                                     | → eval                     |
| 评测看板    | `eval.html`     | 只读指标、校准曲线、badcase                                                  | → mine                     |

## 7. 交互与 Mock 约定

- 数据单源：`mock.js`（`window.DB`：merchants / menus / coupons / agent / eval / dishImg()），页面不得散写数据；接口桩 `api.js`（带 `delay`，`// TODO: replace with …` 标注）。
- 公共层冻结：`styles.css` / `mock.js` / `state.js` / `api.js` / `app.js` / `icons.js` 只由主设计者维护；页面专属样式一律写在该页 `<style>` 内，只准使用契约 tokens。
- 状态单源：`state.js`（`window.Store`）：游客会话、购物车（localStorage 持久化）。
- 假入口统一规则：无实现的入口一律 `class="js-placeholder" data-toast="演示版本仅开放「美食外卖」"`，由 `app.js` 统一绑定 Toast，禁止死链。
- 加购动效：图片抛物线飞入购物车 + 角标弹跳（`flyToCart()`，merchant/agent 页共用）。
- 模拟数据：商家与菜品均为虚构名称，不得使用真实品牌；页脚统一「模拟数据 · 仅用于产品演示」。

## 8. 设计决策记录

1. 首页不放大促/入口 banner（AI 入口 banner 曾试行，评审后移除）；AI 入口收敛为中间 Tab「AI 点外卖」，辅以购物车空态与商家页入口。
2. 分类两排 10 项，仅「美食外卖」真实可点；其余与「消息」Tab 统一为轻提示（比死链更自然）。
3. 商家/菜品使用虚构名（保持真实命名风格，规避商标问题）。
4. 购物车「一键结算」保留真实样式，点击提示「演示版本至加购为止」。
5. 不做凑单推荐面板（省心凑），仅保留底部条起送差额提示。
6. 登录态无过期时间（长期保持），退出登录或清除浏览器数据后重置。
