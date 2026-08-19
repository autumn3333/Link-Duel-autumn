# DESIGN.md —— Link-Duel 设计文档

> 本文回答三个问题:**数据放在哪一层、为什么这样放**;**服务端如何做到"绝不信任客户端"**;**同一局游戏如何保证只结算一次**。

## 1. 总览

```
浏览器 A ──┐                                  ┌── 浏览器 B
           │  STOMP over WebSocket(/ws)       │
           ▼                                  ▼
      ┌─────────────────────────────────────────────┐
      │        Spring Boot 3(单实例,权威裁决)         │
      │  REST(/api/*):登录/匹配/查询/排行榜            │
      │  STOMP(/app/*):move/heartbeat               │
      │  GameSweeper:每 2s 扫描进入超时/倒计时结算      │
      │  WebSocketEventListener:断线 → 立即弃赛结算    │
      └───────┬──────────────────────┬──────────────┘
              │ MyBatis(Flyway 建表) │ StringRedisTemplate
              ▼                      ▼
        MySQL 8(持久真相)      Redis 7(临时状态/协作原语)
        users  game_records    match:queue  room:*  user:game:*
                               games:active  leaderboard:points
```

一句话原则:**MySQL 只存"最终事实"(用户、终局记录、积分);Redis 只存"进行中的事"(队列、房间、在线),并天然带 TTL 会过期。** 这两类数据的生命周期完全不同,混在一个库里既慢又难清理。

## 2. 职责划分与"Redis 为什么不可替代"

| 场景(题目要求) | MySQL 能解决吗 | Redis 的角色 | 为什么 Redis 合适 |
|---|---|---|---|
| 匹配队列 | 可以,但要建表 + 轮询/锁,且清理麻烦 | `match:queue` ZSET + **Lua 原子配对** | 入队/取队/删队都是 O(log N) 原语;Lua 脚本在 Redis 内单线程原子执行,并发 join 不会出现"同一个人被配两次"或"两个人各自配到对方产生两间房";无 TTL,靠在线状态过滤清理 |
| 对局进行中的临时状态(棋盘、比分、截止时间、在线标志) | 不行——写入频率高(每步一写),且"未完成"数据没有长期价值 | `room:{roomId}` JSON,TTL 30 分钟 | 题目明确要求"不依赖进程内存":服务端重启对局照常恢复,正是因为有 Redis 而不是内存 Map;30 分钟 TTL 保证进程崩溃/断线死局也能自然过期,不污染存储 |
| 在线/重连状态 | 不适合——高频读写、易失数据 | `user:online:{userId}` String,TTL 30s | 心跳(前端 5s + STOMP 协议层 10s)持续续期;掉线自动过期。值绑定 WS sessionId,解决"旧连接断线事件误伤新连接"的竞态 |
| 实时排行榜 | 可以做(每局结算后重算),但每局全表扫描不划算 | `leaderboard:points` ZSET,ZADD 绝对值 | ZREVRANGE O(log N);ZADD 绝对值天然幂等;启动时从 MySQL 全量重建,永远可以自愈 |
| 结算互斥 | 单靠 MySQL 唯一键只能防"双写",不能防"双结算流程同时跑" | `lock:settle:{roomId}` SETNX EX 30 | Redis 先做一道跨请求的粗粒度互斥,MySQL 唯一键 `uk_game_id` 做最终防线,两级配合 |

对应关系:题目要求的三条 Redis 场景(匹配队列 / 未结束对局状态与重连 / 实时排行榜)分别由 `match:queue`、`room:*` + `user:online:*`、`leaderboard:points` 覆盖,没有一条是"为用而用"。

## 3. 选型取舍

| 决策 | 选择 | 理由与备选 |
|---|---|---|
| 后端 | Spring Boot 3.4(Java 17) | 题目允许 Node/Spring Boot 任选;团队技术栈 |
| 实时通信 | STOMP over WebSocket(SimpleBroker) | 自带 PUB/SUB 路由(`/topic` 广播、`/user/queue` 单播)、心跳、CONNECT 鉴权扩展点(ChannelInterceptor);对比裸 WebSocket 省掉自研协议,对比 Socket.IO 少一层私有封装 |
| 持久层 | 原生 MyBatis(XML Mapper) | SQL 直观可控、便于面试讲解;对比 JPA 无黑盒 |
| 建表 | Flyway(V1__init.sql) | 启动自动建表,评审永远不需要手动碰数据库(题目硬要求);需额外依赖 flyway-mysql |
| 鉴权 | 轻量 JWT(jjwt)+ HandlerInterceptor + ChannelInterceptor | REST 与 STOMP 各一个拦截器验同一枚 token;不引入 Spring Security 全量配置,避免课程未覆盖的复杂度;BCrypt 用 spring-security-crypto 独立包 |
| 游戏规则 | 服务端权威三消引擎(Match3Engine,纯静态)+ 前端事件驱动动画 | 客户端只发 `{from, to}` 两个坐标、只回放事件,不做任何规则预判;引擎纯静态 + 显式注入 Random,单测完全确定性、可复现 |
| 结算幂等 | Redis SETNX + JVM 房间锁 + MySQL 事务 + 唯一键 + 修复路径 | 见 §8,单事务内"插记录 + 改积分"使提交成为唯一落盘点 |

## 4. 对局状态机

```
        ┌──────────┐   60s 无人加入   ┌────────────┐
        │ WAITING  ├──────────────────►│  CANCELLED │(join_timeout,双方 0 分)
        └────┬─────┘                   └────────────┘
             │ 双方均进入房间(SUBSCRIBE 校验通过)
             ▼
        ┌──────────┐
        │ PLAYING  │◄─── 合法交换:每消除 1 格 +1,广播 moved + cleared(状态不变)
        └────┬─────┘
             │ 倒计时到期(GameSweeper)→ SETTLED(timeout,按分结算)
             │ 一方离线(断线事件,立即)→ SETTLED(forfeit,在线方胜 +3)
             ▼
        ┌──────────┐   广播 gameover → 清理 Redis → 写 MySQL
        │ SETTLED  │
        └──────────┘
```

### 断线规则(立即结算)

- 断线 = WS 断开。`user:online` 立即删除(排队中→移出匹配队列);对局中玩家断线 → **立即** `settleGame(FORFEIT)`,在线方胜 +3,广播 gameover,双方 UI 弹结算回大厅(无宽限期)
- 自己重连恢复:STOMP 退避重连 + 订阅自动恢复 + 服务端重推全量快照(对手未断线时对局不受影响)
- 服务端重启:两端 WS 同时断开 → 先处理到的断线事件判在线方胜、后处理到的看到房间已结算直接跳过(对局状态仍在 Redis,重启后正常开始新对局)
- 旧连接晚到的 DISCONNECT:比对 `user:online` 里的 sessionId,不匹配则忽略,防止"重连成功后又被旧断线事件判离线"

### 计分

对局内每消除 1 格 +1(连锁累加,记给操作者,双方比分实时同步);终局积分:胜 +3 / 平局各 +1 / 负 0 / 弃赛胜者 +3 / 取消 0。积分写入用户表为**绝对值**(UPDATE 计算结果),排行榜 ZADD 同样用绝对值,双重天然幂等。

## 5. 关键算法

### 5.1 三消引擎(Match3Engine,面试重点)

8×8 棋盘全满,`cellId = row*8 + col`,6 种图案;引擎纯静态、全部随机行为显式接收 `Random`(单测固定种子完全可复现):

```
findMatches:    逐行/逐列扫描连续相同图案的 run,长度 ≥3 的格子取并集(支持十字/丁字交叉)
resolveSwap:    交换 from/to → findMatches 为空则交换回来、返回 null(非法)
                循环:消除 → 按列下落(收集未消除图案自底向上填回)→ 顶部随机补块
                每循环一轮记一个 Step{cells, board(该步下落补块后的快照)}
                返回 MoveResult{steps, scoreGained=总消除格数}
hasValidSwap:   枚举全部正交相邻对(右邻+下邻即可全覆盖),试探交换是否产生三连
generate:       逐格随机填充,候选图案与左侧/上方两格相同则重抽 → 初始无三连;
                整盘再校验 hasValidSwap,无解则重生成(≤10 次)
reshuffle:      收集全部图案洗牌重排,直到 hasValidSwap(≤10 次)
```

- 连锁:补块可能立即形成新三连,外层循环继续,直到无匹配——一次交换可能产生多步 cleared 事件
- 死局:每次操作结束后若无可行交换,自动洗牌直到有解(不限次数,与"只允许一次"的旧规则不同)
- 单测 10 例:行/列/十字匹配、交换合法性(非法必须还原棋盘)、连锁(下落 + 补块的两步确定性用例)、死局检测、生成器无初始三连且有解、洗牌保图案集合必有解

### 5.2 事件驱动的连锁动画(前端纯展示)

客户端不预演规则:收到 `moved`(交换)+ 每条 `cleared`(该连锁步的消除格与下落补块后的完整棋盘)后,按队列依节奏回放——交换 200ms → 消除 250ms → 下落 300ms。方块以稳定 key 身份驱动 Vue TransitionGroup 的 FLIP 动画:交换/下落 = 同 key 换位置(移动过渡),消除 = dying 缩放消失,补块 = 新 key 从顶部入场。快照到达时整体重置队列与棋盘。

### 5.3 匹配的原子性(match.lua)

并发 join 时,如果"取队列前 2 人"不是原子操作,会出现双重配对。因此在**一个 Lua 脚本内**完成:

```
ZADD match:queue <now> <userId>          -- 入队
循环 ≤10 次:
  ZPOPMIN 取 2 人                        -- 原子弹出
  对每个人 EXISTS user:online 过滤掉线   -- 掉线者已离开,直接丢弃
  若只弹出 1 人:按原入队时间 ZADD 放回(FIFO 不乱),继续循环
返回 [0]=未配对  或 [2]=配对成功的两人
```

Redis 单线程执行 Lua,整个配对过程对其他请求不可见。崩溃残留的队列条目靠 EXISTS 过滤 + 玩家重新 join 时天然处理。

## 6. 服务端权威校验清单("绝不信任客户端")

客户端每次 `move` 只发 `{from, to}` 两个数字,其余全部由服务端推导:

| # | 校验 | 拦截的作弊 |
|---|---|---|
| 1 | JWT 有效(ChannelInterceptor 拦 CONNECT;HandlerInterceptor 拦 REST) | 未登录调用 |
| 2 | roomId **不由客户端传**——从 `user:game:{userId}` 推导 | 给别人的房间发 move |
| 3 | SUBSCRIBE `/topic/game/{roomId}` 时校验是房间成员且未结算 | 偷听/骚扰他人对局 |
| 4 | move 时校验:在局中、是成员、状态 playing | 未开局/已结束/非成员操作 |
| 5 | 坐标范围合法、from≠to | 越界/自消 |
| 6 | 两格正交相邻 | 隔空交换 |
| 7 | Match3Engine(服务端版)判定交换后形成 ≥3 连 | 乱换一通 |
| 8 | `now >= deadline` → 触发结算并拒绝 | 超时后偷分 |
| 9 | 得分只在服务端累加、比分只随 moved/cleared 广播下发 | 伪造分数 |
| 10 | 终局胜负、积分增减只在 SettlementService 计算 | 伪造胜利 |

前端**不镜像任何规则**:非法交换由服务端经 `/user/queue/errors` 回 `42200`(不相邻)/`42204`(无法形成三连),客户端只负责提示与回放动画。

## 7. 数据设计

### 7.1 MySQL(持久真相,Flyway 自动建表)

```sql
users(id, email UNIQUE, password_hash /*BCrypt*/, nickname,
      points, wins, losses, draws, created_at, updated_at)
game_records(id, game_id VARCHAR(40) UNIQUE /*=Redis roomId,幂等防线*/,
      player_a_id, player_b_id, score_a, score_b, winner_id /*NULL=平局/取消*/,
      status /*finished|forfeit|cancelled*/, reason /*timeout|forfeit|join_timeout*/,
      started_at, ended_at, created_at)
```

utf8mb4 全库(棋盘图案是 Emoji,4 字节)。种子账号由 `DataSeeder`(ApplicationRunner)幂等创建:按 email 查,不存在才插入。

### 7.2 Redis(临时状态,全部带 TTL 或可重建)

| key | 类型 | 内容 | TTL | 为什么这个 TTL |
|---|---|---|---|---|
| `match:queue` | ZSET | member=userId,score=入队毫秒 | 无 | 离线者由 Lua EXISTS 过滤;无僵尸累积 |
| `user:online:{userId}` | String | 值="1"(仅 REST)或 WS sessionId | 30s | 心跳续期;掉线 30s 内自动过期;sessionId 防旧断线误伤 |
| `room:{roomId}` | String | RoomState JSON:双方 id、board[64]、分数、status、startedAt、deadline、online 标志 | 30min | 覆盖断线重连窗口;结束后即删,崩溃残留 30min 自然过期 |
| `user:game:{userId}` | String | roomId | 30min | 重连索引(`/api/game/current` 用);结算时删除 |
| `lock:settle:{roomId}` | String | SETNX 互斥锁 | 30s | 结算流程秒级完成,30s 兜底防死锁 |
| `games:active` | ZSET | member=roomId,score=下次动作时间(WAITING=创建+60s,PLAYING=deadline) | 无 | GameSweeper 每 2s 按 score 扫描;结算即删 |
| `leaderboard:points` | ZSET | member=userId,score=积分 | 无 | 从 MySQL 全量重建,任何不一致最终自愈 |

`roomId = "r-" + 6 位随机 hex`,同时作为 `game_records.game_id` —— 一个 ID 讲通 Redis 房间与 MySQL 记录。

## 8. 结算幂等:同一局永远只结算一次

所有结束路径(倒计时超时/离线弃赛/进入超时)汇入 `SettlementService.settleGame(roomId, trigger)`:

```
1. SET lock:settle:{roomId} NX EX 30     → 拿不到锁:有人正在结算,直接返回
2. JVM 内 per-room ReentrantLock          → 串行化同进程的 move vs settle
3. 读 room:不存在或已 settled → 释放返回
4. 计算结果(winner/reason/终局分数;forfeit 取仍在线的一方)
5. MySQL 单事务 = 提交点:
     INSERT game_records(game_id UNIQUE)  ← 唯一键,先插后改
     SELECT ... FOR UPDATE users          ← 行锁,防并发积分读写
     UPDATE users SET points/wins/...(绝对值)
6. 正常:ZADD leaderboard(绝对值) → 广播 gameover → 标记 settled
7. 清理 room / user:game×2 / games:active → 释放锁
```

**崩溃窗口分析**(每一步崩溃/重复/并发会发生什么):

| 故障点 | 表现 | 自愈 |
|---|---|---|
| 事务提交前崩溃 | INSERT 未落库 | 锁 30s 过期 → 下次 sweep 重新结算,MySQL 无记录,无重复 |
| 事务内崩溃 | 事务回滚 | 同上,零残留 |
| **提交成功、ZADD/广播前崩溃** | 库里已有记录,但排行榜/客户端不知 | 重复结算请求触发 DuplicateKeyException → **修复路径**:读已存在的 game_records(权威结果)重放 ZADD + 重广播,不再改积分 |
| 同一局被两个请求并发结算 | 两个线程同时进 | SETNX 只有一人拿到锁;锁内还有 JVM 房间锁;最坏情况 MySQL 唯一键兜底 |
| 服务端重启 | 内存锁全丢 | Redis 锁存在;锁过期后 game_records 唯一键仍然是防线;排行榜启动时从 MySQL 全量重建 |

三层防线:Redis SETNX(互斥)→ JVM 房间锁(同进程串行)→ MySQL `uk_game_id` + 事务(最终真相)。**先插 game_records 再改 users 的顺序是关键**:插入成功即结算生效,后续任何失败都能从这张表重放。

## 9. STOMP 协议

endpoint `/ws`(原生 WebSocket,不用 SockJS;Vite 代理 `/ws`);CONNECT 头带 `Authorization: Bearer <jwt>`,ChannelInterceptor 校验后 `setUser(Principal)`,使 `/user/queue/*` 可路由到本人。心跳:服务端 10s/10s(必须给 SimpleBroker 配 TaskScheduler,否则静默失效)+ 业务心跳 5s。

| 目的地 | 方向 | 消息 |
|---|---|---|
| `/app/heartbeat` | C→S | `{}` → 回 `/user/queue/heartbeat` `{serverNow}`,续期 user:online |
| `/app/game/move` | C→S | `{from, to}`(roomId 由服务端推导) |
| `/user/queue/match` | S→C | `match-found {roomId, opponent}` |
| `/user/queue/errors` | S→C | `error {code, message}`(NOT_ADJACENT/SAME_CELL/INVALID_SWAP/NOT_IN_GAME/GAME_OVER/NOT_YOUR_ROOM…) |
| `/user/queue/heartbeat` | S→C | `{serverNow}` → 客户端校准时钟偏移,倒计时不受本机时钟影响 |
| `/user/queue/snapshot` | S→C | 全量快照(订阅房间后 ~200ms 延迟推送,避开订阅竞态) |
| `/topic/game/{roomId}` | S→C | 事件信封 `{type, serverNow, data}` |

事件:`snapshot` / `moved{byUserId,from,to,scoreA,scoreB}`(交换)/ `cleared{byUserId,cells,board,scoreA,scoreB}`(每连锁步一条,board 为该步下落补块后的完整棋盘)/ `reshuffled{board}`(死局自动洗牌)/ `started{startedAt,deadline}`(等待→开战)/ `gameover{winnerId,scoreA,scoreB,reason,deltaA,deltaB}`。所有事件携带 `serverNow`,客户端每收到一条即校准一次偏移。

## 10. 时间与扫描

- 全部时间戳用 **epoch 毫秒**(游戏内),数据库存 DATETIME(Asia/Shanghai),两者只在落库时转换,避免时区坑
- `GameSweeper` `@Scheduled(fixedRate = 2000)` 扫 `games:active`(ZSET 按"下次动作时间"排序,天然延迟队列):WAITING 超 60s → 取消;PLAYING 过 deadline → 超时结算。**离线结算不走扫描**——由 WebSocketEventListener 的断线事件立即触发,保证"对手离线秒回大厅"的体验

## 11. 扩展方向(当前单实例假设)

1. **多实例**:结算需 Redis 分布式锁(或 Redisson)+ 跨实例事件广播(pub/sub);房间锁从 JVM 移到分布式
2. 水平分片用户表 / 对局记录按月分区
3. 匹配加段位权重(ZSET score 组合 ELO 与时间)
4. 观战:只读订阅 `/topic/game/{roomId}`(当前拦截器校验成员,扩展为成员+观战白名单)
5. 前端按需引入 Element Plus、代码分包减小首屏
