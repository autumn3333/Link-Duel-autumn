# 🍎 Link-Duel —— 1v1 在线对战消消乐(交换三消)

两名玩家实时匹配进入同一房间、拿到同一棋盘,在 2 分钟倒计时内**拖动相邻方块交换**,横/竖凑成 3 个及以上相同图案即消除,上方方块下落、自动补块、连锁消除;实时看到对方的每一步;服务端权威校验 + 幂等结算 + 实时排行榜;**对手离线立即判你获胜(+3)并结算回大厅**。

## 玩法与计分

- 棋盘:8×8 = 64 格,6 种水果图案(🍎🍌🍇🍓🍊🍉),棋盘永远全满
- 操作:**拖动(或点选)两个相邻方块交换**;交换后至少形成一处横/竖 ≥3 连才算合法(支持十字交叉)
- 消除后上方方块下落、顶部随机补块,连锁消除直到无三连为止
- 每消除 1 格 +1 分(连锁累加),记给操作者,双方实时看到动画与比分
- 对局时长 2 分钟,倒计时结束按分结算;**死局(无可行交换)自动洗牌直到有解**,不限次数
- 结算积分:**胜 +3 / 平局各 +1 / 负 0**;对手离线 → 立即结算,在线方胜(+3),双方弹结算回大厅
- 断线重连:自己短暂断网自动重连恢复(对局状态存 Redis,刷新页面即可恢复);对手掉线则按上一条立即结算

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Vue Router + @stomp/stompjs + Element Plus |
| 后端 | Spring Boot 3.4(Java 17)+ Maven;STOMP over WebSocket;MyBatis(XML);Flyway 建表;JWT(jjwt)+ BCrypt |
| 数据 | MySQL 8(用户/对局记录/结算)+ Redis 7(匹配队列/对局状态/在线重连/排行榜) |
| 环境 | docker-compose 一键拉起 MySQL + Redis(评审无需手动碰数据库) |

## 环境要求

- Docker Desktop(提供 MySQL 8 + Redis 7)
- JDK 17+、Maven 3.8+
- Node.js 18+(npm)

## ⚡ 3 分钟快速启动

以下命令在项目根目录(Git Bash)依次复制执行:

```bash
# 0. 确认 Docker Desktop 已启动(Windows 11 家庭版需 WSL2:
#    管理员 PowerShell 执行 wsl --install,按提示重启一次)
docker info

# 1. (可选)环境变量——compose 里全部有默认值,不复制也能直接跑;只有想改端口才复制
cp .env.example .env

# 2. 启动 MySQL(宿主机 3307 端口)+ Redis(6379)
docker compose up -d

# 3. 等两个容器 healthy(重要:后端依赖 MySQL 就绪后才建表;首次约 30~60 秒)
docker compose ps
```

```bash
# 4. 启动后端(自动建表 + 自动创建种子账号;首次运行下载依赖需几分钟,后续秒起)
cd server && mvn spring-boot:run
```

```bash
# 5. 另开一个终端,启动前端(有锁文件,推荐 npm ci,更快更稳定;装过依赖后直接 npm run dev)
cd client && npm ci && npm run dev
```

浏览器打开 <http://localhost:5173> 即到登录页,登录页有一键填充按钮。

### 国内网络拉不动镜像 / npm 依赖?

`docker compose up -d` 若卡在 Pulling / 报 registry-1.docker.io 连接超时,先经镜像源拉取并打回标准标签(只需一次),再重跑 `docker compose up -d`:

```bash
docker pull docker.1ms.run/library/mysql:8.0      && docker tag docker.1ms.run/library/mysql:8.0      mysql:8.0
docker pull docker.1ms.run/library/redis:7-alpine && docker tag docker.1ms.run/library/redis:7-alpine redis:7-alpine
docker compose up -d
```

`npm ci` 若下载缓慢,可切换 npm 镜像源:

```bash
npm config set registry https://registry.npmmirror.com
```

## 演示账号

| 玩家 | 邮箱 | 密码 | 昵称 |
|---|---|---|---|
| 玩家 A | player_a@example.com | Test123456! | 玩家A |
| 玩家 B | player_b@example.com | Test123456! | 玩家B |

账号由后端启动时自动创建(DataSeeder,幂等),评审全程不需要碰数据库。

## 双人对战步骤

1. Chrome 普通窗口登录「玩家 A」,另开一个**隐身窗口**登录「玩家 B」(`Ctrl+Shift+N`;标签页共享 localStorage,必须分窗口),登录页有一键填充按钮
2. 两个窗口依次点击「开始匹配」→ 自动配对,进入同一房间,双方看到相同棋盘
3. **拖动**一个方块滑到相邻格(或点选两个相邻格)交换;凑成三连即消除、下落、连锁,对方窗口动画与比分同步
4. 2 分钟倒计时结束 → 按分结算弹窗;返回大厅可见排行榜已更新
5. 断线演示:关掉玩家 B 的窗口 → A 侧立刻弹出「对手离线,你获胜 +3」并返回大厅;重开 B 的窗口自动回到大厅
6. 自我恢复演示:刷新 A 的页面(或短暂断网)→ 自动重连并恢复对局

## 测试

```bash
# 三消引擎纯单元测试(无需数据库):匹配检测/交换合法性/连锁/下落补块/死局/洗牌,10 例
cd server && mvn test -Dtest='Match3EngineTest'

# 全量测试(需先 docker compose up -d;测试用独立 Redis 库 15,测试用户与记录自动清理,可重复运行、不污染排行榜)
cd server && mvn test
```

覆盖:登录鉴权、匹配成房(Redis Lua)、结算幂等(重复触发只落一行)、崩溃修复路径、定时扫描(进入超时/倒计时到期)、对手离线立即结算、STOMP 全流程(WebSocketStompClient 模拟双客户端真实对战)。

## 常用调试命令

```bash
docker compose ps                       # 容器状态(两个都要 healthy)
docker exec -it linkduel-redis redis-cli
#   KEYS *;ZRANGE match:queue 0 -1 WITHSCORES;GET room:r-xxxxxx
#   ZREVRANGE leaderboard:points 0 9 WITHSCORES
docker exec -it linkduel-mysql mysql -ulinkduel -plinkduel123 linkduel
#   SELECT * FROM game_records ORDER BY id DESC LIMIT 5;

docker compose down -v                  # ⚠️ 清空数据库与 Redis(重置到初始状态)
```

## 目录结构

```
.
├── docker-compose.yml      # MySQL 8 + Redis 7(utf8mb4,健康检查)
├── .env.example            # 环境变量模板(复制为 .env;.env 不入库)
├── DESIGN.md               # 设计文档(状态机/Redis key/幂等/STOMP 协议)
├── AI_USAGE.md             # AI 辅助开发说明
├── server/                 # Spring Boot 3 后端
│   └── src/main/java/com/linkduel/
│       ├── controller/     # REST:登录/匹配/对局查询/排行榜
│       ├── ws/             # STOMP:连接鉴权、事件入口、断线监听(立即结算)、事件发布
│       ├── service/        # 匹配(Lua)、对局(权威校验)、结算(幂等)、排行榜、在线状态
│       ├── game/           # Match3Engine(三消引擎:匹配检测/交换结算/连锁/洗牌)、GameSweeper(定时扫描)
│       ├── security/       # JWT(jjwt)
│       ├── mapper/         # MyBatis 接口 + resources/mapper/*.xml
│       └── seed/           # 种子账号(BCrypt)
│   └── src/main/resources/
│       ├── db/migration/V1__init.sql   # Flyway 自动建表
│       └── lua/match.lua               # 原子配对脚本
└── client/                 # Vue 3 前端
    └── src/
        ├── views/          # 登录 / 大厅(匹配、排行榜)/ 对局页(事件回放队列)
        ├── components/     # 棋盘(方形格子、拖拽交换、FLIP 下落动画)、玩家面板、倒计时、结果弹窗
        ├── api/            # http 封装 + ws.ts(STOMP 客户端:退避重连、订阅恢复、时钟校准)
        ├── stores/         # Pinia(auth)
        └── utils/          # 常量(图案/文案/错误码)
```

## 已知限制

- 单实例部署:结算依赖 JVM 内房间锁 + Redis SETNX;多实例需分布式锁 + 跨实例广播,见 DESIGN.md 扩展方向
- JWT 密钥为开发默认值,生产必须通过环境变量 `JWT_SECRET` 覆盖(HS256,≥32 字节)
- 对局状态在 Redis 保留 30 分钟(TTL),覆盖断线重连窗口;期间未结束的对局由定时扫描结算
- 前端为演示规模,Element Plus 全量引入(产物偏大,不影响功能)

## 安全说明

仓库不含任何生产密钥:数据库口令只存在于 .env.example 的本地开发默认值;.env 已被 .gitignore 忽略;密码一律 BCrypt 存储;JWT 密钥支持环境变量注入。
