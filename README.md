# Wathe 源码说明文档

Wathe 是一个基于 **Minecraft 1.21.1 + Fabric** 的列车狼人杀 / 社交推理玩法 Mod。本项目在原 Wathe 的基础上做了较多自改，重点方向是：

- 让服务器能围绕“杀手、平民、义警、中立”等阵营组织一局对局；
- 支持地图投票、多维度地图、地图变量、地图增强配置和地图重置；
- 提供心情 / 任务 / 商店 / 金币 / 毒药 / 尸体 / 回放 / 本能透视等核心系统；
- 把职业扩展常用的 mixin 注入点整理成公开 API，方便 NoellesRoles、StupidExpress、KinsWathe、StarryExpress 等扩展职业 Mod 接入；
- 通过 HarpyModLoader 这类加载层，把本体职业位替换为扩展职业池，实现更复杂的职业局。

如果把整个玩法看成一台列车桌游引擎，那么 Wathe 本体负责“开局、分配、胜负、基础资源和 UI”，扩展 Mod 负责“新职业、新技能、新结算规则和新商品”。

## 运行环境

| 项目 | 当前配置 |
| --- | --- |
| Minecraft | `1.21.1` |
| Fabric Loader | `0.16.10` |
| Java | `21+` |
| Mod 版本 | `1.3.3-1.21.1` |
| Gradle 插件 | Fabric Loom |
| 主要依赖 | Fabric API、Cardinal Components API、MidnightLib、Ratatouille、DataSync、Simple Voice Chat API |

常用开发命令：

```powershell
.\gradlew build
.\gradlew runClient
.\gradlew runServer
```

源码入口由 `src/main/resources/fabric.mod.json` 定义：

| EntryPoint | 类 | 作用 |
| --- | --- | --- |
| `main` | `dev.doctor4t.wathe.Wathe` | 服务端 / 通用初始化入口 |
| `client` | `dev.doctor4t.wathe.client.WatheClient` | 客户端渲染、HUD、按键、音效、模型入口 |
| `fabric-datagen` | `dev.doctor4t.wathe.datagen.WatheDatagen` | 数据生成入口 |
| `cardinal-components` | `dev.doctor4t.wathe.cca.WatheComponents` | CCA 组件注册入口 |
| `voicechat` | `dev.doctor4t.wathe.compat.TrainVoicePlugin` | Simple Voice Chat 兼容入口 |

## 目录导览

| 路径 | 说明 |
| --- | --- |
| `src/main/java/dev/doctor4t/wathe/Wathe.java` | Mod 总初始化：注册物品、方块、命令、网络包、数据包重载、回放格式器、任务点系统等 |
| `src/main/java/dev/doctor4t/wathe/api` | 给本体和扩展 Mod 使用的公开 API：职业、阵营、游戏模式、商店、经济、胜利、任务完成、本能、HUD 等 |
| `src/main/java/dev/doctor4t/wathe/api/client/inventory` | 背包按钮公开 API：扩展按钮注册、三类背包 screen type、动态分组、分页和头像辅助 |
| `src/main/java/dev/doctor4t/wathe/api/client/hud` | 通用屏幕 HUD 叠加 API：右下角职业状态、全屏遮罩、狙击镜等自由绘制入口 |
| `src/main/java/dev/doctor4t/wathe/cca` | Cardinal Components 状态组件：世界状态、玩家状态、计分板全局状态 |
| `src/main/java/dev/doctor4t/wathe/game` | 对局生命周期、游戏模式、地图效果、地图重置任务 |
| `src/main/java/dev/doctor4t/wathe/command` | 管理员和玩家指令 |
| `src/main/java/dev/doctor4t/wathe/client` | 客户端 HUD、界面、渲染、模型、按键逻辑 |
| `src/main/java/dev/doctor4t/wathe/config/datapack` | 地图投票和地图增强 JSON 读取 |
| `src/main/java/dev/doctor4t/wathe/item` | 匕首、枪、毒药、鸡尾酒、钥匙、手雷等物品逻辑 |
| `src/main/java/dev/doctor4t/wathe/block` | 列车方块、门、床、座椅、托盘、灯、玻璃等方块逻辑 |
| `src/main/java/dev/doctor4t/wathe/entity` | 尸体、手雷、爆竹、纸条、座位等实体 |
| `src/main/java/dev/doctor4t/wathe/record` | 对局事件记录和回放文本生成 |
| `src/main/java/dev/doctor4t/wathe/task` | 任务点扫描、同步和客户端透视数据 |
| `src/main/resources/assets/wathe` | 材质、模型、语言文件、音效、粒子 |
| `map_datapack_template` | 地图投票数据包模板 |
| `README_SHOP_CURRENCY_API.md` | 商店多货币系统专项教程 |

## 启动初始化流程

`Wathe.onInitialize()` 是服务端和通用逻辑的总入口，主要做这些事：

1. 初始化 `GameConstants`，载入对局时长、任务、商店、冷却、金钱等常量。
2. 注册 `data/wathe/maps/*.json` 的数据包重载器，供地图投票和地图增强系统使用。
3. 初始化物品、方块、实体、粒子、声音、方块实体、DataComponent。
4. 注册自定义命令参数：时间、游戏模式、地图效果。
5. 注册所有 `/wathe:*` 管理员指令和 `/instinct key` 玩家指令。
6. 注册 C2S / S2C 网络包，例如开枪、刺杀、商店购买、任务完成、地图投票、手雷模式同步。
7. 注册方块交互黑名单，让地图数据包能禁止玩家右键某些装饰方块。
8. 注册床效果、回放格式器和对局事件钩子。
9. 监听玩家加入 / 离开，用于 supporter 检查、地图投票状态同步、晚加入玩家传送、回放记录。
10. 初始化任务点扫描 / 同步系统和调度器。

本项目有一处重要自改：`Wathe.executeSupporterCommand(...)` 已经取消 supporter 二次校验，只保留 Brigadier 指令本身的 OP 权限判断。也就是说，本地调试和服务器管理员可以正常执行原本需要 supporter entitlement 的测试指令。

## 对局生命周期

核心流程在 `GameFunctions` 和 `GameWorldComponent` 里。

### 状态机

`GameWorldComponent.GameStatus` 有四个状态：

| 状态 | 含义 |
| --- | --- |
| `INACTIVE` | 未开局，大厅 / 等待阶段 |
| `STARTING` | 开局黑屏淡入淡出阶段 |
| `ACTIVE` | 对局进行中 |
| `STOPPING` | 结算 / 停局黑屏阶段 |

### 开局流程

管理员执行 `/wathe:start` 后，大致流程是：

1. `StartCommand` 读取当前世界的 `GameWorldComponent`、`GameMode` 和 `MapEffect`。
2. `GameFunctions.startGame(...)` 检查地图投票是否还在进行。
3. 从 `MapVariablesWorldComponent.readyArea` 里统计准备区玩家。
4. 如果人数满足当前模式的 `minPlayerCount`，进入开局流程。
5. 如果开启了渐进式地图重置，先执行 `MapResetTask`，完成后再进入 `STARTING`。
6. `GameWorldComponent.tickCommon()` 推进黑屏计时，时间到后调用 `GameFunctions.initializeGame(...)`。
7. `baseInitialize(...)` 会清理旧状态、设置游戏规则、切换玩家模式、重置玩家组件、复制地图、应用地图增强、分配房间和钥匙。
8. 当前 `GameMode.initializeGame(...)` 分配职业和初始物品。
9. `GameEvents.ON_FINISH_INITIALIZE` 触发，给扩展 Mod 一个“所有初始分配完成后”的稳定事件点。
10. `GameRecordManager.completeInitialization(...)` 记录本局初始职业快照，之后局中转职才会被记录成转职事件。

### 死亡流程

玩家死亡统一走 `GameFunctions.killPlayer(...)`：

- 将玩家切到旁观模式，清理心情任务，必要时生成尸体实体；
- 根据死亡原因记录回放事件；
- 支持毒药、床毒、自定义武器、自定义死亡原因等额外 NBT 数据；
- 触发 `AllowPlayerDeath` 事件，扩展职业可以实现护盾、免死、替死等逻辑；
- 通过 `DeathApi` 暴露死亡请求、致死确认前、切旁观后、心情重置前、尸体生成时和死亡结束后的分阶段钩子；
- 根据 `ShouldDropOnDeath` 判断死亡时掉落哪些物品；
- 平民死亡会增加倒计时惩罚时间；
- 杀手击杀后获得金币收益，并刷新已使用的 Derringer。

源码里保留了旧 4 参 `killPlayer(victim, spawnBody, killer, deathReason)` 的完整方法体，同时新增 5 参重载临时挂载额外回放数据。旧扩展仍能暂时兼容，但新增死亡奖励、反噬、尸体标记、特殊存活和击杀收益归属应优先接 `DeathApi`，不要再 mixin `GameFunctions.killPlayer(...)` 的局部变量或返回点。

### 结算流程

Murder 模式每 tick 判断：

- 时间耗尽，通常乘客 / 时间结算；
- 所有平民死亡，杀手胜利；
- 所有杀手阵营玩家死亡，乘客胜利；
- 如果扩展 Mod 注册了 `VictoryApi` 规则，则先交给自定义胜利规则仲裁。

最终 `GameRoundEndComponent` 保存结算数据，`GameFunctions.stopGame(...)` 切到 `STOPPING`，淡出结束后 `finalizeGame(...)` 负责清理、回放、地图投票等后续流程。

## 内置游戏模式

注册表在 `WatheGameModes`：

| ID | 类 | 玩法 |
| --- | --- | --- |
| `wathe:murder` | `MurderGameMode` | 主要狼人杀模式。分配平民、杀手、义警；杀手杀光平民胜利，乘客找出并清除杀手胜利 |
| `wathe:discovery` | `DiscoveryGameMode` | 探索 / 展示模式。所有玩家都是 `DISCOVERY_CIVILIAN`，时间耗尽后结束 |
| `wathe:loose_ends` | `LooseEndsGameMode` | 大乱斗模式。所有玩家获得 Loose End 职业和武器，最后存活者获胜 |

`WatheGameModes.registerGameMode(id, gameMode)` 可注册扩展模式。HarpyModLoader 就会注册类似 `harpymodloader:modded` 的模式，用于在 Murder 规则基础上替换职业池。

## 职业、阵营与角色表

核心类：

- `Role`：职业对象，包含 ID、职业颜色、是否按平民处理、是否能使用杀手功能、心情类型、最大冲刺时间、是否能看时间。
- `Faction`：更明确的阵营语义，当前有 `CIVILIAN`、`VIGILANTE`、`KILLER`、`NEUTRAL`。
- `WatheRoles`：职业注册表。
- `GameWorldComponent.roles`：当前世界 / 当前对局里的玩家 UUID 到职业映射。

内置职业：

| 职业 ID | 阵营 | 说明 |
| --- | --- | --- |
| `wathe:civilian` | 平民 | 真实心情，会接任务，普通乘客 |
| `wathe:vigilante` | 义警 | 真实心情，默认会获得左轮 |
| `wathe:killer` | 杀手 | 假心情，可使用杀手商店和杀手本能 |
| `wathe:loose_end` | 中立 | Loose Ends 模式使用 |
| `wathe:discovery_civilian` | 平民 | Discovery 模式使用，无心情任务 |

旧 Wathe 主要依赖 `isInnocent()` 和 `canUseKiller()` 判断职业语义，扩展多了之后容易混淆。本项目增加了 `Faction`：

- 玩家名字可以按阵营色显示；
- 职业名可以继续按职业自己的颜色显示；
- 结算、回放、职业替换、扩展模式可以明确判断“杀手 / 中立 / 义警 / 平民”。

扩展职业推荐这样注册：

```java
public static final Role MY_NEUTRAL = WatheRoles.registerNeutralRole(new Role(
        MyMod.id("my_neutral"),
        0xE0B637,
        false,
        false,
        Role.MoodType.FAKE,
        WatheRoles.CIVILIAN.getMaxSprintTime(),
        true
));
```

## CCA 组件设计

Wathe 用 Cardinal Components API 保存大量状态，入口是 `WatheComponents`。

### WorldComponent

| 组件 | 作用 |
| --- | --- |
| `TrainWorldComponent` | 列车视觉状态：速度、HUD、雪、雾、时间等 |
| `GameWorldComponent` | 当前世界的对局状态、职业映射、模式、地图效果、房间、碰撞、跳跃、渐进式重置等 |
| `MapVariablesWorldComponent` | 地图坐标：出生点、旁观点、准备区、游戏区、模板区、粘贴偏移 |
| `MapEnhancementsWorldComponent` | 当前维度地图增强配置的同步缓存 |
| `WorldBlackoutComponent` | 停电状态 |
| `GameTimeComponent` | 对局倒计时 |
| `AutoStartComponent` | 自动开局设置 |
| `GameRoundEndComponent` | 结算数据 |
| `TaskPointWorldComponent` | 当前地图任务点缓存 |

### EntityComponent

| 组件 | 作用 |
| --- | --- |
| `PlayerLifeStateComponent` | 特殊玩法存活授权，例如旁观 / 创造但仍按局内存活计算 |
| `PlayerMoodComponent` | 心情、任务、低心情幻视、任务完成逻辑 |
| `PlayerShopComponent` | 金币和多货币余额、购买入口 |
| `PlayerPoisonComponent` | 毒药来源、毒发计时、毒药回放数据 |
| `PlayerPsychoComponent` | 疯魔模式状态 |
| `PlayerNoteComponent` | 纸条编辑状态 |
| `PlayerGrenadeComponent` | 手雷直投 / 蓄力模式偏好 |
| `PlayerInstinctComponent` | 本能键开关 / 长按模式偏好 |

### ScoreboardComponent

| 组件 | 作用 |
| --- | --- |
| `ScoreboardRoleSelectorComponent` | 杀手 / 义警权重、强制职业、职业位抽取 |
| `MapVotingComponent` | 全服唯一的地图投票状态 |
| `GameRoundEndComponent` | 计分板层级也注册了一份结算组件，用于跨维度读取 |

地图投票放在 ScoreboardComponent 上是很关键的设计：玩家会被传送到不同维度，如果投票状态绑在某个世界上，切图后容易丢状态或读错世界。

## 地图变量、地图增强与地图投票

Wathe 的地图系统分为两层。

### 地图变量

`MapVariablesWorldComponent` 保存一张地图运行所需的基础坐标：

- 大厅出生点；
- 旁观出生点；
- 准备区；
- 游戏区域；
- 地图重置模板区域；
- 模板粘贴偏移；
- 默认游戏模式和地图效果。

这些值主要通过 `/wathe:mapVariables set ...` 在游戏内配置，并写进世界 CCA。

### 地图增强 JSON

`data/wathe/maps/*.json` 由 `MapEnhancementsConfigurationReloader` 读取。模板在 `map_datapack_template`。

一个地图 JSON 可以声明：

- `dimension`：地图所在维度；
- `display_name`、`description`：地图投票界面显示；
- `min_players`、`max_players`：人数限制；
- `enhancements.rooms`：房间、房间名、容量、出生点；
- `scenery`：背景景物范围；
- `visibility`：昼 / 夜 / 黄昏视距；
- `fog`：雾起点、终点、夜晚颜色；
- `camera_shake`：室内 / 室外镜头晃动；
- `snow_particles`：雪粒子数量和范围；
- `interaction_blacklist`：禁止交互方块或方块标签；
- `gravity`、`movement`、`jump`：重力、移速、跳跃规则；
- `visual`：静态地图、HUD、列车速度、时间；
- `ambience`：室内 / 室外环境音；
- `special_roles.enabled_roles`：给扩展 Mod 读取的地图特殊职业白名单。

简化示例：

```json
{
  "dimension": "wathe:template_map",
  "display_name": "Template Map",
  "description": "A sample Wathe map.",
  "min_players": 4,
  "max_players": 16,
  "enhancements": {
    "visual": {
      "static_map": false,
      "hud": false,
      "train_speed": 100,
      "time_of_day": "NIGHT"
    },
    "jump": {
      "allowed": true,
      "stamina_cost": 0.0
    },
    "rooms": [
      {
        "name": "Room A",
        "max_players": 2,
        "spawn_points": [
          { "x": 10.5, "y": 65.0, "z": 20.5, "yaw": 180.0, "pitch": 0.0 }
        ]
      }
    ]
  }
}
```

### 地图投票

`MapVotingComponent` 负责全服地图投票：

1. 从数据包读取所有地图。
2. 按当前在线人数过滤 `min_players` / `max_players`。
3. 如果设置了 `randommapcount`，随机抽取候选地图。
4. 玩家通过 UI 或 `/wathe:mapvote <index>` 投票。
5. 如果所有需要投票的人都投完，自动把剩余时间缩短到 5 秒。
6. 投票结束后按票数做加权随机，而不是简单最高票。
7. 进入转盘动画阶段。
8. `GameFunctions.finalizeVoting(...)` 把所有世界里的玩家迁移到选中维度，并刷新重生点。

如果只有一张合格地图，系统会直接选中它，不进入投票界面。

## 地图重置与渐进式重置

Wathe 的地图通常是“模板区复制到游戏区”。配置项来自：

- `resetTemplateArea`：模板区域；
- `resetPasteOffset`：模板区到游戏区的偏移。

`GameFunctions.tryResetTrain(...)` 会按方块、方块实体、非完整方块的顺序复制，并清理旧尸体、掉落物、爆竹、纸条等实体。

本项目新增了 `MapResetTask` 渐进式重置：

- 默认开启；
- `/wathe:setGradualReset false` 可以关闭；
- 开局前先分批恢复地图，避免一次性复制大量方块造成卡顿；
- 重置期间会给玩家显示“地图重置中 xx%”；
- 完成后再进入 `STARTING` 黑屏阶段。

## 心情与任务系统

核心组件是 `PlayerMoodComponent`。

### 心情类型

`Role.MoodType` 有三种：

| 类型 | 含义 |
| --- | --- |
| `REAL` | 真实心情，会掉心情、刷任务、任务完成回血 |
| `FAKE` | 假心情，通常给杀手 / 中立伪装用，HUD 表现可像有心情但不按真实平民任务规则死亡 |
| `NONE` | 无心情，不参与心情任务 |

### 任务生成规则

本项目对原任务系统做了重要调整：

- 心情值限制在 `0.0 ~ 1.0`；
- 只要身上有任意任务，就按单任务速度掉心情，不再按任务数量叠加掉心情；
- 第一个任务仍按冷却刷新；
- 低心情会按阈值临时开放第二 / 第三个并行任务槽；
- 扩展 Mod 可以通过 `MoodTaskApi.assignRandomTasks(...)` / `fillRandomTaskSlots(...)` 主动发放随机心情任务，不必等待心情跌到阈值；
- 外部主动发放任务仍然和自动任务共用最多 3 个同时任务的上限；
- 心情回升后不会强行删除已有任务，但不会继续补新任务；
- 完成一个任务只移除该任务，不会清空全部任务；
- 长期卡住的任务会在其他任务完成若干次后自动移除，但不奖励心情；
- 任务完成会记录回放，并触发 `TaskCompletionApi`。

内置任务包括：

| 任务 | 条件 |
| --- | --- |
| `sleep` | 睡觉 |
| `outside` | 靠近能看到天空的位置 |
| `water` | 接触水 |
| `fire` | 靠近火焰或点燃的营火 |
| `shift` | 潜行 |
| `stare` | 近距离看着其他存活玩家 |
| `away` | 远离其他存活玩家 |
| `eat` | 吃食物 |
| `drink` | 喝鸡尾酒 |
| `run` | 疾跑 |
| `sit` | 坐在座位上 |
| `potion` | 喝药水 |
| `music` | 右键音符盒 |
| `book` | 阅读讲台上的书 |
| `stay` | 原地静止 |
| `fish` | 钓鱼收获 |
| `cook` | 从熔炉 / 烟熏炉取出熟食 |

### 任务点透视

`TaskPointScanner` 会扫描当前地图实际游戏区域，找出床、火源、水、座位、音符盒、讲台、炉子、托盘、带钥匙门等任务点。扫描范围不是全世界，而是根据 `resetTemplateArea + resetPasteOffset` 推导当前列车区域，并且必须落在 `playArea` 内。

客户端按 `Y` 可切换任务点透视。管理员可以用：

- `/wathe:taskPoints reload`：重新扫描并同步；
- `/wathe:taskPoints refresh`：只重新发送缓存；
- `/wathe:taskPoints autoRefresh true|false`：控制每局开局是否自动重扫。

## 商店与经济系统

核心类：

- `PlayerShopComponent`：保存玩家金币和多货币余额，处理购买请求；
- `EconomyApi`：声明货币、余额 HUD、被动收入资格、被动收入修改器；
- `ShopApi`：解析玩家当前可见商品、职业商店、商店修改器、购买交付；
- `ShopEntry`、`ShopPrice`、`ShopPayment`、`CurrencyAmount`：商品和价格结构。

默认逻辑：

- 杀手能力角色默认有金币 HUD 和被动金币收入；
- 注册了商店或经济规则的扩展职业也可以显示金币 HUD；
- 商店购买由 Wathe 统一扣钱、播放音效、发送失败提示、记录回放；
- 开发环境下如果余额不足，会自动补测试资金，方便调试购买流程；
- `balance` 旧字段仍保留，用作 `wathe:money` 的镜像，兼容旧扩展源码；
- 多货币 API 仍保留，但默认杀手商店当前已恢复为纯金币价格，任务币发放常量目前暂停。

更完整的多货币教程见 `README_SHOP_CURRENCY_API.md`。

扩展职业注册专属商店示例：

```java
ShopApi.registerRoleShop(MY_ROLE, player -> List.of(
        ShopEntry.giveToInventory(
                new ItemStack(MyItems.SPECIAL_TOOL),
                ShopPrice.money(150),
                ShopEntry.Type.TOOL
        )
));
```

## 本能、HUD 与客户端体验

客户端入口是 `WatheClient`。

主要功能：

- 注册实体 / 方块实体渲染器；
- 注册粒子工厂、模型加载插件、方块透明 / 裁剪渲染层；
- 锁定部分客户端选项，例如 gamma、视距、字幕、自动跳跃、云、音量分类；
- 注册背景环境音：列车内、列车外、疯魔模式低频音；
- 处理黑屏淡入淡出时的主音量变化；
- 渲染回合开始 / 结束文本、商店 HUD、时间 HUD、心情 HUD、任务点透视；
- 自动打开地图投票界面；
- 处理手雷投掷模式、本能键、地图投票键、任务点键。

默认按键：

| 按键 | 功能 |
| --- | --- |
| `Left Alt` | 本能 |
| `Y` | 任务点透视开关 |
| `U` | 地图投票界面 |
| 手持手雷左键 | 切换直投 / 蓄力投掷模式 |

本能系统已经 API 化：

- `InstinctApi.registerAvailability(...)` 决定某玩家当前是否能开启本能；
- `InstinctApi.registerHighlight(...)` 决定某个目标显示什么描边颜色；
- 默认杀手本能和旁观者本能只是 priority 0 的默认 handler；
- 扩展职业可以给非杀手职业增加本能，也可以屏蔽或改写目标颜色。

`/instinct key toggle|hold|check` 可切换本能键模式：

- 开关模式：按一下打开，再按一下关闭；
- 长按模式：按住才生效。

### 通用 HUD 叠加 API

职业/词条需要画“自由位置”的屏幕 HUD 时，优先接入 `dev.doctor4t.wathe.api.client.hud` 包，不要再给 `InGameHud` 写扩展 mixin。这个 API 面向右下角状态文字、全屏遮罩、狙击镜、开局安全提示等通用叠加内容；如果只是心情条、顶部时间、准心图标、准心名字或背包按钮，应优先使用对应的专用 API。

核心类型：

- `HudOverlayApi.register(id, layer, priority, renderer)`：注册任意 HUD provider。
- `HudOverlayApi.registerAliveRole(id, layer, priority, role, renderer)`：注册职业 HUD，并自动要求本地玩家是该职业且符合 Wathe 的 `GameFunctions.isPlayerAliveAndSurvival(...)` 存活定义。
- `HudOverlayLayer.BEFORE_HUD`：在原版 HUD 前绘制，适合控制、绑架、开局安全提示这类要尽早盖住画面的内容。
- `HudOverlayLayer.MAIN_HUD`：在 Wathe 主 HUD 后绘制，适合常规右下角职业状态。
- `HudOverlayLayer.AFTER_HUD`：在全部 HUD 后绘制，适合狙击镜等必须压在最上层的遮罩。
- `HudOverlayContext`：提供 `client`、`player`、`gameWorld`、`drawContext`、`textRenderer`、屏幕尺寸、`aliveAndSurvival`、debug/HUD 隐藏状态和 `renderHotbar()`。
- `HudOverlayLayout`：提供常用的右下角多行文字和准心附近居中绘制工具。

常规职业状态示例：

```java
HudOverlayApi.registerAliveRole(
        MyMod.id("hud/my_role/status"),
        HudOverlayLayer.MAIN_HUD,
        HudOverlayApi.DEFAULT_PRIORITY,
        MY_ROLE,
        context -> HudOverlayLayout.drawBottomRightLine(
                context,
                Text.translatable("hud.mymod.my_role.ready"),
                MY_ROLE.color()
        )
);
```

全屏遮罩或狙击镜这类不是“某职业右下角文字”的 HUD 可以直接 `register(...)`，但必须在 provider 内自己判断存活状态：

```java
HudOverlayApi.register(MyMod.id("hud/my_role/scope"), HudOverlayLayer.AFTER_HUD, 1000, context -> {
    if (!context.aliveAndSurvival()) {
        return;
    }
    context.drawContext().fill(0, 0, context.width(), context.height(), 0xAA000000);
    context.renderHotbar();
});
```

### 准心图标公开 API

职业/物品需要替换屏幕中心的 crosshair 图标时，优先接入 `dev.doctor4t.wathe.api.client.gui.CrosshairHudApi`，不要再 mixin `CrosshairRenderer`。这个 API 只负责 3x3 准心和准心下方 10x7 小图标；准心名字、尸体文字和同伙提示仍然走 `RoleNameHudApi`，狙击镜大遮罩仍然走 `HudOverlayApi`。

核心入口：

- `CrosshairHudApi.registerProvider(id, priority, provider)`：短路接管默认准心。返回 `PASS` 继续交给低优先级 provider 或 Wathe 默认准心；返回 `HANDLED` 表示本帧已经处理完，可以是已绘制自定义准心，也可以是故意隐藏默认准心。
- `CrosshairHudApi.registerOverlay(id, priority, renderer)`：默认准心后追加绘制，不会阻止默认准心和其他 overlay，适合怀表冷却条这类只补一条进度条的场景。
- `CrosshairHudApi.Context`：提供 `client`、`player`、`drawContext`、`tickCounter`、主手物品、`tickDelta` 和屏幕中心坐标。
- `renderStandardCrosshair(...)`、`renderKnifeProgressCrosshair(...)`、`renderBatProgressCrosshair(...)`、`renderIconProgressCrosshair(...)`：复用 Wathe 的准心纹理和 blend 设置，避免扩展复制渲染状态。

准心只是客户端提示，不能作为玩法结果来源。扩展物品在 C2S 包、服务端 use/attack 逻辑里仍然必须重新校验职业、存活、冷却、距离和目标合法性。

## 主要物品和玩法效果

Wathe 本体提供了不少社交推理玩法道具：

| 类型 | 例子 | 说明 |
| --- | --- | --- |
| 近战 / 远程武器 | `knife`、`revolver`、`derringer`、`bat` | 击杀、击退、冷却、回放命中 |
| 道具 | `grenade`、`firecracker`、`crowbar`、`lockpick`、`body_bag`、`note` | 爆炸、引诱、撬门、开锁、藏尸、留言 |
| 毒药 | `poison_vial`、`scorpion`、床 / 托盘毒效果 | 延迟毒发、记录毒源、可被扩展格式化回放 |
| 商店物品 | `blackout`、`psycho_mode` | 停电、疯魔模式 |
| 地图 / 建筑物品 | 门、钥匙、托盘、床、座椅、灯、玻璃、栏杆 | 支撑列车地图和任务系统 |

毒药、床效果、托盘效果都有公开注册点：

- `TrayEffectRegistry`
- `BedEffectRegistry`
- `CanSeePoison`
- `ReplayRegistry` 的 tray / bed formatter

## 回放与对局记录

核心类：

- `GameRecordManager`：采集事件，保存当前对局和上一局记录；
- `GameRecordHooks`：从 Fabric / mixin / 游戏流程中挂接事件；
- `GameRecordTypes`：事件类型常量；
- `ReplayRegistry`：注册事件格式化器；
- `ReplayGenerator`：把记录转成聊天文本并发送。

回放系统分两层：

1. 记录层只保存结构化事件，例如谁买了什么、谁击杀了谁、谁完成了任务。
2. 展示层通过 `ReplayRegistry` 把事件翻译成玩家能读懂的文本。

记录的事件包括：

- 对局开始 / 结束；
- 玩家加入 / 离开；
- 初始职业快照；
- 局中转职；
- 商店购买；
- 物品使用 / 命中 / 拾取；
- 食物 / 饮料 / 托盘 / 床毒；
- 毒药状态；
- 死亡和死亡原因；
- 护盾格挡；
- 技能使用；
- 全局事件；
- 门交互；
- 任务完成；
- 每个玩家结算结果。

局内实时回放只发送给非存活视角，避免活人通过聊天气泡类 Mod 泄露信息。对局结束后，完整回放会发送给所有玩家。

扩展格式化示例：

```java
ReplayRegistry.registerGlobalEventFormatter(
        MyMod.id("ritual_complete"),
        MyReplayFormatters::formatRitualComplete
);

GameRecordManager.recordGlobalEvent(
        world,
        MyMod.id("ritual_complete"),
        caster,
        null
);
```

## 扩展 API 总览

| API / 事件 | 作用 | 典型用途 |
| --- | --- | --- |
| `WatheRoles` | 注册职业和阵营 | 新增杀手、平民、义警、中立职业 |
| `WatheGameModes` | 注册游戏模式 | HarpyModLoader 的 Modded Murder |
| `WatheMapEffects` | 注册地图效果 | 自定义昼夜、列车视觉、开局物品 |
| `GameEvents` | 对局开始 / 初始化完成事件 | 开局赋额外职业、刷新扩展状态 |
| `VictoryApi` | 胜利仲裁 | 独立胜利、保活、共胜 |
| `EconomyApi` | 金币 HUD、被动收入、多货币 | 富豪、任务大师、自定义货币 |
| `ShopApi` | 职业商店和商店修改器 | 给某职业专属商品，或改默认杀手商品 |
| `MoodTaskApi` | 主动发放心情任务 | 技能给目标直接追加随机任务、补满任务槽 |
| `TaskCompletionApi` | 任务完成事件和任务收益 | 任务大师、完成任务减冷却、职业充能 |
| `GunShotApi` | 枪击接管、客户端目标覆写、左轮误伤惩罚、冷却修正 | 自定义手枪、假枪、无声枪、按状态调整左轮冷却 |
| `DeathApi` | 击杀/死亡分阶段钩子、默认击杀收益规则、尸体生成回调 | 赏金奖励、时间狭缝、双重人格致死转化、验尸官尸体数据 |
| `InstinctApi` | 本能资格和描边 | 新职业透视、状态高亮、本能压制 |
| `PlayerLifeStateApi` | 特殊玩法存活状态 | 旁观 / 创造但仍参与胜负和 HUD |
| `MoodHudApi` | 心情 HUD 样式 | 特殊职业心情条 |
| `HudOverlayApi` | 通用屏幕 HUD 叠加 | 右下角职业状态、全屏遮罩、狙击镜、开局安全提示 |
| `CrosshairHudApi` | 准心图标 / 准心下方小进度条 | 扩展武器锁定提示、蓄力条、隐藏默认准心 |
| `RoleNameHudApi` | 准心名字 / 实体名牌 / 准心额外 HUD | 扩展职业名显示规则、非玩家播放体名牌、准心附近提示 |
| `PlayerAppearanceApi` | 玩家外观覆写 | 伪装、变形 |
| `BodyAppearanceApi` | 尸体外观覆写 | 双重人格、伪尸体 |
| `HeldItemInvisibilityApi` | 手持物隐藏 | 某些技能道具对其他活人不可见 |
| `TimeHudApi` | 时间 HUD | 自定义时间显示 |
| `InventoryButtonApi` | 背包按钮生命周期 | 职业选人按钮、图鉴按钮、分页按钮、输入阶段阻止 E 键关闭 |
| `TrayEffectRegistry` | 托盘效果 | 毒药、药剂、陷阱 |
| `BedEffectRegistry` | 床效果 | 床毒、床炸弹 |
| `AllowPlayerDeath` | 死亡拦截 | 护盾、免死、替死 |
| `AllowPlayerPunching` | 攻击权限 | 特殊职业允许 / 禁止拳击 |
| `AllowPlayerOpenLockedDoor` | 锁门权限 | 万能钥匙、职业开门 |
| `ShouldDropOnDeath` | 死亡掉落扩展 | 自定义武器死亡掉落 |
| `CanSeePoison` | 毒药可见性 | 验毒职业、特殊视觉 |
| `RecordEvents` | 对局记录完成 | 生成额外回放、统计数据 |
| `ReplayRegistry` | 回放文本格式器 | 自定义技能 / 物品 / 死亡原因文本 |

### 背包按钮公开 API

背包按钮统一由 `dev.doctor4t.wathe.api.client.inventory` 包提供。扩展 Mod 不需要再 mixin
`LimitedInventoryScreen`、原版 `InventoryScreen` 或 `CreativeInventoryScreen` 来追加按钮；只要在客户端初始化时注册 provider，Wathe 会在对应 screen 打开后统一调度 `init`、`tick`、`render`、`close` 和“背包键是否允许关闭”。

核心类型：

- `InventoryButtonApi`：注册 provider，并负责每个 screen 实例的生命周期调度。
- `InventoryScreenType`：区分 `LIMITED`、`VANILLA`、`CREATIVE` 三种背包界面。
- `InventoryButtonContext`：提供当前 screen、玩家、字体、界面尺寸、背景坐标，以及 `addWidget`、`replaceGroup`、`clearGroup`、`setGroupVisible` 等操作。
- `InventoryButtonExtension`：扩展侧每次打开背包都会创建一个实例，可以保存当前页、已选目标、临时输入状态。
- `InventoryButtonLayout`、`InventoryPageState`、`InventoryPageSwitchWidget`：提供 NoellesRoles 同款居中分页布局、跨 screen 页码缓存和上一页/下一页按钮。
- `InventoryPlayerHeadHelper`：渲染玩家头像时优先读取原始皮肤，避免变形/伪装状态造成“伪装套娃”。

示例：

```java
public static void register() {
    InventoryButtonApi.registerProvider(MyMod.id("inventory/my_role"), InventoryButtonApi.DEFAULT_PRIORITY, context -> {
        if (context.type() != InventoryScreenType.LIMITED || context.player() == null) {
            return null;
        }
        return MyRoleComponent.KEY.get(context.requirePlayer()).canUseButton()
                ? new MyRoleInventoryButtons()
                : null;
    });
}

final class MyRoleInventoryButtons implements InventoryButtonExtension {
    private static final Identifier GROUP = MyMod.id("inventory_group/my_role");

    @Override
    public void init(InventoryButtonContext context) {
        context.addWidget(GROUP, new MyRoleTargetButton(context.requireLimitedScreen(), 0, 0));
    }

    @Override
    public void tick(InventoryButtonContext context) {
        // 动态显示/隐藏整组按钮。需要重建列表时用 replaceGroup 或 clearGroup。
        context.setGroupVisible(GROUP, MyRoleComponent.KEY.get(context.requirePlayer()).canUseButton());
    }

    @Override
    public boolean allowInventoryKeyClose(InventoryButtonContext context, int keyCode, int scanCode) {
        return !MyRoleInputState.isTyping();
    }
}
```

`replaceGroup` / `clearGroup` 的“删除”语义是隐藏并禁用旧 widget，而不是直接从 Minecraft screen 内部列表移除。这样可以稳定兼容不同映射和版本，也能避免误删其他扩展挂到同一个背包里的按钮。需要像召集者这类动态增删列表时，优先把同一组玩家头像和翻页按钮放进同一个 group，再整体重建或显隐。

页码缓存由 Wathe 在开局、停局、结算完成和断线时统一清理。扩展侧只需要使用 `InventoryPageState` 保存某类按钮的当前页，不要自己监听对局重置事件重复清理。

## 扩展工程如何接入

用户给出的几个工程可以按职责理解：

| 工程 | 路径 | 作用 |
| --- | --- | --- |
| HarpyModLoader | `D:\哈比快车最新源码\harpymodloader\HarpyModLoader1` | 扩展职业加载层，注册 modded 游戏模式，按阵营替换职业位，管理强制职业和权重 |
| NoellesRoles | `D:\哈比快车最新源码\noellesroles\NoellesRoles - 副本 - 副本 - 副本5.7.1` | 大型职业扩展，使用职业注册、经济、商店、任务、回放、本能、HUD、床 / 托盘效果等 API |
| StupidExpress | `D:\哈比快车最新源码\stupidexpress\StupidExpress2.1` | 中立 / 独立胜利职业扩展，重点使用 `VictoryApi`、`ShopApi`、`TaskCompletionApi`、`ReplayRegistry` |
| KinsWathe / kinssaba | `D:\哈比快车最新源码\kinswathe\kinssaba` | 职业与词条扩展，包含 Licensed Villain 等自定义胜利，使用商店、经济、任务、本能、HUD API |
| StarryExpress | `D:\哈比快车最新源码\starryexpress\StarryExpress1.3.2\src` | 小型职业扩展，包含 Starstruck、Muzzler、Allergic 等，使用任务完成、本能、商店和回放 API |

HarpyModLoader 的思路是“先让 Wathe 开一局 modded murder，再由加载层按阵营替换默认职业”。例如先给所有人设成 `WatheRoles.CIVILIAN`，再从扩展职业池里挑选杀手、中立、义警、平民职业覆盖。Wathe 本体提供稳定的职业映射、结算、回放和 API，扩展加载层负责“这局到底出现哪些职业”。

## 管理员与玩家指令

大多数 `/wathe:*` 指令要求 OP 权限等级 2。当前源码已经取消 supporter 二次限制，因此管理员和控制台可直接调试。

| 指令 | 作用 |
| --- | --- |
| `/wathe:start` | 开始当前世界的对局 |
| `/wathe:stop` | 停止当前对局，走正常结算 / 清理流程 |
| `/wathe:stop force` | 强制停止 |
| `/wathe:setmode <mode>` | 切换当前世界默认游戏模式 |
| `/wathe:setTimer <time>` | 设置对局倒计时 |
| `/wathe:setMoney <players> <amount>` | 设置玩家金币 |
| `/wathe:setTaskMoney <players> <amount>` | 设置玩家任务币余额 |
| `/wathe:setkiller <count>` | 固定每局杀手数量，`-1` 恢复比例分配 |
| `/wathe:forceRole killer <players>` | 强制某些玩家成为杀手位 |
| `/wathe:forceRole vigilante <players>` | 强制某些玩家成为义警位 |
| `/wathe:gameSettings help` | 查看游戏设置帮助 |
| `/wathe:gameSettings weights check` | 查看权重状态 |
| `/wathe:gameSettings weights reset` | 重置权重 |
| `/wathe:gameSettings set weights <true|false>` | 开关权重系统 |
| `/wathe:gameSettings set autoStart <...>` | 设置自动开局 |
| `/wathe:gameSettings set backfire <chance>` | 设置开枪反噬概率 |
| `/wathe:gameSettings set roleDividend killer <value>` | 设置杀手比例分母 |
| `/wathe:gameSettings set roleDividend vigilante <value>` | 设置义警比例分母 |
| `/wathe:gameSettings set bounds <true|false>` | 开关旁观者限制在游戏区 |
| `/wathe:mapVariables help` | 查看地图变量帮助 |
| `/wathe:mapVariables set gameModeAndMapEffect <mode> <effect>` | 设置地图默认模式和效果 |
| `/wathe:mapVariables set spawnPosition` | 设置大厅出生点为当前位置 |
| `/wathe:mapVariables set spectatorSpawnPosition` | 设置旁观出生点 |
| `/wathe:mapVariables set readyArea ...` | 设置准备区 |
| `/wathe:mapVariables set playAreaOffset ...` | 设置游戏区偏移 |
| `/wathe:mapVariables set playArea ...` | 设置游戏区域 |
| `/wathe:mapVariables set resetTemplateArea ...` | 设置地图重置模板区域 |
| `/wathe:mapVariables set resetPasteOffset ...` | 设置模板粘贴偏移 |
| `/wathe:setVisual snow <true|false>` | 开关雪粒子 |
| `/wathe:setVisual fog <true|false>` | 开关雾 |
| `/wathe:setVisual hud <true|false>` | 开关列车 HUD |
| `/wathe:setVisual trainSpeed <value>` | 设置列车速度 |
| `/wathe:setVisual time <day|night|sundown>` | 设置全服视觉时间 |
| `/wathe:setVisual resetMapEffects` | 重置地图视觉效果 |
| `/wathe:lockToSupporters <true|false>` | 原 supporter 锁指令，当前源码实际始终不锁 |
| `/wathe:moodEffectDeath <true|false|check>` | 开关心情归零死亡 |
| `/wathe:allowjump <true|false|check>` | 开关局内存活玩家跳跃 |
| `/wathe:playerCollision <true|false|check>` | 开关局内存活玩家碰撞 |
| `/wathe:startnoCollision <seconds|check>` | 设置开局无碰撞保护时间 |
| `/wathe:setGradualReset <true|false|check>` | 开关渐进式地图重置 |
| `/wathe:gamemode <player> <mode> [specialAlive]` | 调试用：切换玩家原版游戏模式，并可标记 Wathe 玩法存活 |
| `/wathe:taskPoints reload` | 重扫任务点并广播 |
| `/wathe:taskPoints refresh` | 广播当前任务点缓存 |
| `/wathe:taskPoints autoRefresh <true|false|check>` | 开关开局自动重扫任务点 |
| `/wathe:mapvoting restart` | 重新开始地图投票 |
| `/wathe:mapvoting onlyop <true|false>` | 是否只允许 OP 投票 |
| `/wathe:mapvoting randommapcount <count>` | 每轮随机候选地图数量 |
| `/wathe:mapvote <index>` | 用指令投票给某张地图 |
| `/wathe:giveRoomKey <players> <roomName>` | 给玩家指定房间钥匙 |
| `/instinct key toggle|hold|check` | 设置本能键为开关 / 长按模式，或查看当前模式 |

`UpdateDoorsCommand` 源码存在，但在 `Wathe.java` 中注册被注释掉了，所以 `/wathe:updateDoors` 当前不会出现在游戏里。

## 数据生成、资源和语言

Datagen 入口是 `WatheDatagen`，相关类包括：

- `WatheModelGen`
- `WatheLangGen`
- `WatheItemTagGen`
- `WatheBlockTagGen`
- `WatheBlockLootTableGen`

资源文件集中在 `src/main/resources/assets/wathe`：

- `textures/item`：物品贴图；
- `textures/entity`：实体 / 方块实体贴图；
- `textures/particle`：粒子贴图；
- `lang/*.json`：多语言文本；
- `sounds.json`：音效声明；
- `particles/*.json`：粒子声明。

中文语言文件是 `assets/wathe/lang/zh_cn.json`。

## 当前源码里的重要自改点

这部分是为了让后来维护的人快速知道“这里不是原版 Wathe 行为”。

1. supporter 指令限制已放开：`executeSupporterCommand` 只依赖 OP 权限。
2. `GameWorldComponent.isLockedToSupporters()` 始终返回 `false`，服务器不会真的锁 supporter。
3. 游戏模式最小人数被调低，Murder / Discovery 允许 1 人启动，方便本地测试。
4. 新增或强化了 `Faction` 阵营语义，避免扩展职业继续混用 `isInnocent` 和 `canUseKiller`。
5. 义警左轮延后到最终职业确定后发放，避免扩展义警替换后仍拿到原版左轮。
6. 地图投票支持跨维度晚加入玩家回拉，避免玩家出生在错误世界。
7. 对局记录在所有开局扩展监听器跑完后才锁定初始职业快照，避免扩展职业开局二次赋职被误记成局中转职。
8. 死亡回放支持额外 NBT，并保留旧 `killPlayer` 方法体兼容旧 mixin。
9. 心情系统支持多任务并行、卡死任务自动清理、外部掉心情倍率和保护时间。
10. 任务点系统可扫描地图内实际任务点，并在客户端透视显示。
11. 商店系统改成 `ShopApi`，职业商店、商品修改、统一扣款和回放都走公开入口。
12. 胜利系统改成 `VictoryApi`，独立胜利、保活、共胜不必再 mixin Murder 模式。
13. 本能系统改成 `InstinctApi`，扩展职业可注册自己的可用性和描边颜色。
14. 通用屏幕 HUD 改成 `HudOverlayApi`，扩展职业的右下角状态、全屏遮罩和狙击镜可以统一接入，并默认按 Wathe 存活定义过滤。
15. 玩家跳跃、碰撞、开局无碰撞、心情死亡、渐进式重置都可用指令动态配置。

## 新扩展职业的推荐接入顺序

如果你要写一个新的职业扩展 Mod，推荐按这个顺序做：

1. 在扩展 Mod 初始化时用 `WatheRoles.registerCivilianRole` / `registerKillerRole` / `registerNeutralRole` 注册职业。
2. 如果职业需要专属商店，用 `ShopApi.registerRoleShop` 或 `registerShopModifier`。
3. 如果职业需要金币 HUD / 被动收入，用 `EconomyApi` 注册。
4. 如果职业需要主动给玩家追加心情任务，用 `MoodTaskApi.assignRandomTasks` 或 `fillRandomTaskSlots`。
5. 如果职业和任务完成有关，用 `TaskCompletionApi.AFTER_TASK_COMPLETE` 或任务收益 provider。
6. 如果职业改变胜负，用 `VictoryApi.registerRule`。
7. 如果职业需要本能透视，用 `InstinctApi.registerAvailability` 和 `registerHighlight`。
8. 如果职业需要普通屏幕 HUD，用 `HudOverlayApi.registerAliveRole`；准心图标和准心下方小进度条用 `CrosshairHudApi`；准心名字、实体名牌或准心附近提示用 `RoleNameHudApi`。
9. 如果职业需要记录技能，用 `GameRecordManager.recordSkillUse` / `recordGlobalEvent`，再用 `ReplayRegistry` 注册格式器。
10. 如果职业会伪装、换皮、伪尸体，用 `PlayerAppearanceApi` / `BodyAppearanceApi`。
11. 如果职业会让旁观 / 创造玩家仍参与胜负，用 `PlayerLifeStateApi` 授权，并在清理时撤销。

一个最小的独立胜利规则示例：

```java
VictoryApi.registerRule(MyMod.id("victory/my_role"), VictoryApi.DEFAULT_PRIORITY, context -> {
    List<ServerPlayerEntity> winners = context.alivePlayers().stream()
            .filter(player -> context.gameWorld().isRole(player, MY_ROLE))
            .toList();

    if (winners.size() == 1 && context.alivePlayers().size() == 1) {
        return VictoryApi.VictoryResult.customWin(CustomVictory.of(
                MY_ROLE.identifier(),
                MY_ROLE.color(),
                winners
        ));
    }

    if (!winners.isEmpty() && context.hasVanillaWinner()) {
        return VictoryApi.VictoryResult.keepRunning();
    }

    return VictoryApi.VictoryResult.pass();
});
```

## 维护建议

- 优先使用 `api` 包里的公开入口，尽量减少对 `game`、`cca` 内部字段的 mixin 注入。
- 新增任务枚举时追加到 `PlayerMoodComponent.Task` 末尾，避免旧存档 ordinal 错位。
- 新增回放事件时同时考虑记录字段、格式器和翻译键。
- 新增货币或商店价格时，优先阅读 `README_SHOP_CURRENCY_API.md`。
- 新地图优先从 `map_datapack_template` 复制，再用 `/wathe:mapVariables` 配置坐标。
- 普通职业 HUD 不要新增 `InGameHud` / `CrosshairRenderer` / `RoleNameRenderer` mixin；优先用 `HudOverlayApi`、`CrosshairHudApi`、`RoleNameHudApi`，并删除已被 API 替代的旧 mixin 配置。
- 涉及胜负、死亡、复活、旁观 / 创造存活状态时，务必统一使用 `GameFunctions.isPlayerAliveAndSurvival(...)` 和 `PlayerLifeStateApi`，不要只看原版 `isSpectator()`。
- 涉及职业阵营时，优先使用 `role.getFaction()` 或 `GameWorldComponent.isFaction(...)`。

这份 README 主要说明 Wathe 本体。具体扩展职业的玩法细节，应在各自扩展工程中继续补充单独文档。
