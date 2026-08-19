# 🍎 Link-Duel —— 1v1 在线对战连连看

两名玩家实时匹配进入同一房间、拿到同一棋盘,在 2 分钟倒计时内消除相同图案的格子对(连线转弯 ≤ 2 次),实时看到对方的每一步;服务端权威校验 + 幂等结算 + 实时排行榜;断线 90 秒宽限期内重连可恢复未结束的对局(对局状态存 Redis,刷新页面甚至重启服务端都能恢复)。

## 玩法与计分

- 棋盘:8×8 = 64 格,8 种水果图案(🍎🍌🍇🍓🍊🍉🍒🥝)各 4 对 = 32 对
- 消除规则:两个**相同图案**,连线**转弯不超过 2 次**,中间只能穿过已消除的空格,禁止绕棋盘边缘
- 每消除一对 +1 分,双方实时看到动画与比分
- 对局时长 2 分钟;倒计时结束或棋盘清空即结算;剩余图案无解时自动洗牌一次(仍无解则结算)
- 结算积分:**胜 +3 / 平局各 +1 / 负 0**;一方离线超 90 秒判在线方胜(+3);双方离线对局取消(0)
- 断线重连:宽限期内重新打开页面自动恢复(快照重新推送,倒计时继续)

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
3. 轮流点击两个相同图案消除;对方窗口实时播放连线动画、比分同步
4. 2 分钟倒计时结束或棋盘清空 → 双方弹出结算结果;返回大厅可见排行榜已更新
5. 断线演示:关掉玩家 B 的窗口 → A 侧显示「对手已离线(90 秒宽限)」;90 秒内重新打开 B → 自动恢复对局;超时 → A 获胜(+3)
6. 彩蛋演示:对局进行中重启后端(Ctrl+C 再 `mvn spring-boot:run`)→ 前端自动重连恢复 —— 证明对局状态不依赖进程内存

## 测试

```bash
# 纯单元测试(无需数据库):路径校验 + 棋盘生成,24 例
cd server && mvn test -Dtest='PathValidatorTest,BoardGeneratorTest'

# 全量测试(需先 docker compose up -d;测试用独立 Redis 库 15,测试用户与记录自动清理,可重复运行、不污染排行榜)
cd server && mvn test
```

覆盖:登录鉴权、匹配成房(Redis Lua)、结算幂等(重复触发只落一行)、崩溃修复路径、定时扫描(超时/判负)、STOMP 全流程(WebSocketStompClient 模拟双客户端真实对战)。

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
│       ├── ws/             # STOMP:连接鉴权、事件入口、断线监听、事件发布
│       ├── service/        # 匹配(Lua)、对局(权威校验)、结算(幂等)、排行榜、在线状态
│       ├── game/           # PathValidator(≤2 转弯算法)、BoardGenerator、GameSweeper(定时扫描)
│       ├── security/       # JWT(jjwt)
│       ├── mapper/         # MyBatis 接口 + resources/mapper/*.xml
│       └── seed/           # 种子账号(BCrypt)
│   └── src/main/resources/
│       ├── db/migration/V1__init.sql   # Flyway 自动建表
│       └── lua/match.lua               # 原子配对脚本
└── client/                 # Vue 3 前端
    └── src/
        ├── views/          # 登录 / 大厅(匹配、排行榜)/ 对局页
        ├── components/     # 棋盘、SVG 消除路径动画、玩家面板、倒计时、结果弹窗
        ├── api/            # http 封装 + ws.ts(STOMP 客户端:退避重连、订阅恢复、时钟校准)
        ├── stores/         # Pinia(auth)
        └── utils/          # 前端镜像路径校验(仅 UX 预判)+ 常量
```

## 已知限制

- 单实例部署:结算依赖 JVM 内房间锁 + Redis SETNX;多实例需分布式锁 + 跨实例广播,见 DESIGN.md 扩展方向
- JWT 密钥为开发默认值,生产必须通过环境变量 `JWT_SECRET` 覆盖(HS256,≥32 字节)
- 对局状态在 Redis 保留 30 分钟(TTL),覆盖断线重连窗口;期间未结束的对局由定时扫描结算
- 前端为演示规模,Element Plus 全量引入(产物偏大,不影响功能)

## 安全说明

仓库不含任何生产密钥:数据库口令只存在于 .env.example 的本地开发默认值;.env 已被 .gitignore 忽略;密码一律 BCrypt 存储;JWT 密钥支持环境变量注入。
