# MealMind · AI 外卖决策平台

用一句自然语言完成外卖决策：给出 3–5 张带逐维度评分与依据的候选卡片，硬性约束（预算、忌口、营业状态）由代码保证零违规，整条决策链路的成本与延迟可评测。

个人作品集项目：商家与菜品全部为模拟数据，不含交易与结算；重点不在供给规模，而在决策质量的可测量性。

## 核心特性

- **一句话决策**：自然语言需求 → 推荐卡片（含维度分、档位说明、置信度），3 轮对话内可直接加入购物车
- **硬约束零违规**：预算 / 忌口 / 营业状态由约束引擎代码过滤，不依赖模型判断
- **追问与偏好记忆**：信息不足时最多 1 轮 2 问；口味 / 预算 / 场景偏好跨 run 重复出现后自动沉淀为长期记忆，删除为墓碑软删
- **多轮修正与换一批**：修正需求新建继承 run 增量重算；换一批读已落库候选重建卡片，零模型成本秒回
- **可评测可计量**：Golden 用例集、打分稳定性与校准、降级链路、单次决策成本（`cost_yuan`）与端到端 P95 在评测看板可查

## 架构

```
移动端 H5               业务服务 mealmind-api              Agent 运行时 mealmind-agent
React + Vite   ──/api──▶  Spring Boot :8080     ──I1/SSE──▶  FastAPI + LangGraph :8000
                          │  约束过滤 / 加权排序 / 落库               │
                          ├─ MySQL（决策与偏好账本）                  ├─ 决策模型（百炼 System One 打分，可降级规则分）
                          └─ Redis（缓存 / 进度流 / 断线恢复）         └─ qwen3.8（理解与表达）
```

边界原则：**模型负责语义，代码负责确定性与账本**。候选过滤、加权排序、事件编号（`seq`）、落库与成本计量都在业务服务完成；Agent 不接任何存储，模型密钥只存在于 Agent 容器。

三层之间通过内部 HTTP 与 SSE 通信；事件只有一个出口（`understand` / `clarify` / `scoring` / `card` / `done` 等），断线携带 `Last-Event-ID` 重连可补齐后续事件。

## 技术栈

| 层                      | 技术                                                                                |
| ----------------------- | ----------------------------------------------------------------------------------- |
| 前端 `apps/h5`          | React 18 · TypeScript · Vite · React Router · Zustand · TanStack Query              |
| 业务服务 `services/biz` | Java 21 · Spring Boot 3.5 · MyBatis-Plus · MySQL · Redis · SSE                      |
| Agent `services/agent`  | Python · FastAPI · LangGraph（理解 → 追问 → 过滤 → 打分 → 表达）                    |
| 模型                    | decision-model-preview（阿里云百炼决策模型，System One 打分） · qwen3.8-max-0902（理解与表达） |
| 交付                    | Docker Compose（三容器编排）· 共享 MySQL / Redis 基础设施                           |

## 目录结构

```
apps/h5           移动端 H5（React + TS + Vite）
services/biz      业务服务（Spring Boot，端口 8080）
services/agent    Agent 运行时（FastAPI + LangGraph，端口 8000）
deploy            compose 编排、SQL（建库 / 建表 / 种子数据 / Golden 用例）、环境变量样例
docs              PRD、技术方案、设计契约
prototype         高保真可交互原型（纯静态 HTML/CSS/JS，file:// 可直接打开）
```

## 本地启动

前置：Docker；`infra-net` 网络上已有 MySQL 与 Redis（模拟服务器上的共享基础设施，不随本仓库启动）。

```bash
# 1. 仅首次：创建与服务器同名共享网络
docker network create infra-net

# 2. 准备环境变量（DECISION_* / DASHSCOPE_API_KEY 等，不提交仓库）
cp deploy/.env.example deploy/.env

# 3. 仅首次：初始化数据库，按序号执行
#    sed "s/__MYSQL_PASSWORD__/$MYSQL_PASSWORD/g" deploy/sql/00_bootstrap.sql | mysql -uroot -p
#    10_schema.sql → 11_m4_alter.sql → 20_seed.sql → 30_golden_cases.sql

# 4. 用本地源码构建并启动三个容器（web / api / agent）
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.local.yml up -d --build --wait

# 5. 前端开发服务器（/api 代理到 127.0.0.1:8080）
cd apps/h5 && npm ci && npm run dev   # http://localhost:5173
```

`compose.yml` 只编排三个应用容器、不映射宿主机端口（生产由边缘 nginx 反代）；`compose.local.yml` 为本地叠加：源码构建镜像、api 暴露回环 8080。

## 文档

| 文档                                               | 内容                                                                            |
| -------------------------------------------------- | ------------------------------------------------------------------------------- |
| [docs/PRD.md](docs/PRD.md)                         | 产品需求：目标、场景、功能表、评测口径与完成标志                                |
| [docs/tech-design.md](docs/tech-design.md)         | 技术方案：接口契约（22 个对外接口 + 内部接口）、数据模型、降级状态机、部署与 CI |
| [docs/design-contract.md](docs/design-contract.md) | 设计契约：原型的视觉与结构唯一依据                                              |

## 里程碑

| 阶段          | 内容                                                            | 状态                                |
| ------------- | --------------------------------------------------------------- | ----------------------------------- |
| M1 平台骨架   | 登录、首页、商家、购物车、种子数据                              | ✅ 已完成                           |
| M2 决策链路   | 理解、约束过滤、打分、表达、加购联动                            | ✅ 已完成                           |
| M3 评测与降级 | Golden 集、稳定性与校准、降级状态机、评测看板                   | ✅ 已完成                           |
| M4 记忆与打磨 | 偏好记忆卡、追问、修正 / 换一批、搜索、动效、成本计量、部署上线 | 🚧 功能已完成并验证；部署上线进行中 |

## 说明

- 打分通道：2026-10-12 原 Jev（TeamoRouter）下线，平替为阿里云百炼 `decision-model-preview`（同 System One 协议，限时免费），默认 `SCORER_MODE=AUTO`（决策模型优先、失败自动降级规则分）。
- 演示降级横幅：`AUTO` 下让决策模型不可达（改错 `DECISION_BASE_URL` / 断网）即整轮落 FULL 档——降级是真实行为，不是模拟开关；`SCORER_MODE=RULE` 为强制对照通道（不触发降级）。
