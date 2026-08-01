# Wathe / Harpy / 扩展职业通用开发说明

本文件是当前这套 Wathe 自改源码与多个扩展职业 Mod 的通用协作规则。后续接到“新增功能、修 bug、接口公开化、扩展职业接入、编译联调”等任务时，先按这里的路径和流程读源码，再决定修改范围。

## 当前有效路径

旧 txt 中出现过的 `C:\Users\Huancat\Desktop\...`、旧版 `Wathe - 副本1 - 副本`、旧 `KinsWathe1.6.3` 等路径只作为历史语境；除非用户明确要求参考旧版，否则以后以本文件下面的路径为准。

| 项目 | 路径 | 用途 |
| --- | --- | --- |
| Wathe 当前主工程 | `D:\哈比快车最新源码\wathe\Wathe - 副本1` | MC 1.21.1 Fabric 类狼人杀玩法本体，优先在这里做公共 API 和核心机制。 |
| Wathe 需求 txt | `D:\哈比快车最新源码\wathe\txt要求` | 用户历史需求、最近提示词和复盘材料。 |
| 通用提示词目录 | `D:\哈比快车最新源码\wathe\txt要求\开发通用模板提示词` | 放后续可复用的简化提示词。 |
| HarpyModLoader | `D:\哈比快车最新源码\harpymodloader\HarpyModLoader1` | 加载扩展职业、替换职业池、强制职业、权重和词条分配。 |
| NoellesRoles | `D:\哈比快车最新源码\noellesroles\NoellesRoles - 副本 - 副本 - 副本5.7.1` | 大型扩展职业源码，Yarn 命名，很多 Wathe API 接入示例。 |
| StupidExpress | `D:\哈比快车最新源码\stupidexpress\StupidExpress2.1` | 扩展职业 / 词条 / 独立胜利源码，Mojang 官方映射。 |
| kinssaba | `D:\哈比快车最新源码\kinswathe\kinssaba` | KinsWathe 自改扩展职业源码，Yarn 命名，包含商店、经济、词条、本能和 RoleName HUD 示例。 |
| StarryExpress | `D:\哈比快车最新源码\starryexpress\StarryExpress1.3.2` | StarryExpress 工程根目录，源码在 `...\src`，Mojang 官方映射。构建必须在父目录执行。 |

## 接任务时的固定流程

1. 先读用户本次需求；如果用户明确说“先不修改源码 / 先分析方案”，本轮只分析方案，不改文件。
2. 读取相关 txt，尤其是用户点名的 txt；如果需求属于最近类似改动，也用 `rg` 在 `D:\哈比快车最新源码\wathe\txt要求` 里找同类材料。
3. 读取当前工程 README、`build.gradle`、`gradle.properties`、`fabric.mod.json` 和相关源码入口。不要只靠历史提示词或记忆判断。
4. 用 `rg` 搜类名、方法名、物品 id、mixin 名、翻译 key、回放事件 id，再定位最小修改范围。
5. 能用 Wathe 公开 API 时优先用 API，少写深层 mixin；确实要 mixin 时，条件必须尽量窄，并区分服务端 / 客户端环境。
6. 玩法数值、价格、时长、距离、颜色以外的平衡参数，优先集中放进 `GameConstants` 或对应职业 `*Constants`。
7. 关键代码必须写详细中文注释，尤其是 API 接入理由、服务端 / 客户端边界、同步、回合清理、胜利仲裁、商店扣款、外观优先级这些容易误解的位置。
8. 如果改了 Wathe API，先编译 Wathe，把新 jar 放进相关扩展 `libs`，再编译受影响扩展，不能只编译单个工程就结束。
9. 如果用户提到旧会话 id，但当前无法读取到完整会话内容，不要猜；应先说明缺失并询问，或者只基于本地未提交改动和源码事实继续。

## 必读源码入口

### Wathe 本体

- `README.md`：当前自改 Wathe 的总说明。
- `README_SHOP_CURRENCY_API.md`：商店多货币、默认价格读取和扩展接入教程。
- `src/main/java/dev/doctor4t/wathe/Wathe.java`：总初始化、命令、网络包和系统注册。
- `src/main/java/dev/doctor4t/wathe/game/GameConstants.java`：核心常量、默认商店、冷却、被动收入上限、任务币实验开关。
- `src/main/java/dev/doctor4t/wathe/game/GameFunctions.java`：开局、死亡、停局、地图重置、玩家存活判定。
- `src/main/java/dev/doctor4t/wathe/game/gamemode/MurderGameMode.java`：Murder 模式胜负、被动收入和循环。
- `src/main/java/dev/doctor4t/wathe/cca/GameWorldComponent.java`：对局状态、角色表、跳跃 / 碰撞 / 地图状态。
- `src/main/java/dev/doctor4t/wathe/cca/PlayerShopComponent.java`：金币、多货币余额、购买结算。
- `src/main/java/dev/doctor4t/wathe/cca/PlayerMoodComponent.java`：心情和任务完成入口。
- `src/main/java/dev/doctor4t/wathe/client/gui/StoreRenderer.java`：右上角货币和商店价格渲染。
- `src/main/java/dev/doctor4t/wathe/client/gui/CrosshairRenderer.java`：Wathe 局内准心图标、武器锁定和小进度图标渲染。
- `src/main/java/dev/doctor4t/wathe/client/gui/RoleNameRenderer.java`：准心玩家名 / 同伙提示 / 额外 HUD。
- `src/main/java/dev/doctor4t/wathe/client/gui/MoodRenderer.java`、`TimeRenderer.java`：心情和时间 HUD。
- `src/main/java/dev/doctor4t/wathe/api/client/hud/*`：通用屏幕 HUD 叠加 API，扩展职业右下角状态、全屏遮罩和狙击镜优先看这里。
- `src/main/java/dev/doctor4t/wathe/mixin/client/ui/InGameHudMixin.java`：Wathe 统一调度 `HudOverlayApi` 的客户端注入点，扩展侧一般不应再直接 mixin 这个类。

### Wathe API

优先检查 `src/main/java/dev/doctor4t/wathe/api` 下已有公开接口：

- 职业与阵营：`Role`、`Faction`、`WatheRoles`、`WatheGameModes`
- 商店：`ShopApi`、`ShopPrice`、`ShopPayment`、`RoleShopProvider`、`ShopPurchaseContext`
- 经济：`EconomyApi`、`CurrencyDefinition`、`CurrencyAmount`
- 心情任务：`MoodTaskApi`、`MoodTaskDefinition`
- 心情任务点透视：`MoodTaskPointApi`、`TaskPointDefinition`、`TaskPointScanContext`
- 任务完成：`TaskCompletionApi`
- 胜利：`VictoryApi`
- 枪击 / 左轮反火：`GunShotApi`、`GunShotContext`、`GunTargetContext`、`RevolverPenaltyContext`
- 击杀 / 死亡阶段：`DeathApi`、`DeathContext`、`BodySpawnContext`
- 本能：`InstinctApi`
- 玩家存活：`PlayerLifeStateApi`
- 玩家 / 尸体可见与可选中：`TargetVisibilityApi`
- 外观：`PlayerAppearanceApi`、`BodyAppearanceApi`
- 通用屏幕 HUD：`HudOverlayApi`、`HudOverlayContext`、`HudOverlayLayer`、`HudOverlayLayout`
- 准心图标 / 准心下方小进度条：`CrosshairHudApi`
- 准心名字 / 实体名牌 / 准心额外 HUD：`RoleNameHudApi`
- 心情 HUD：`MoodHudApi`
- 时间 HUD：`TimeHudApi`
- 疯魔模式：`PsychoModeApi`、`PsychoModeProfile`、`PsychoShieldContext`、`PsychoShieldResult`、客户端 `PsychoModeClientApi`
- 手持物隐藏：`HeldItemInvisibilityApi`
- 背包按钮：`InventoryButtonApi`、`InventoryScreenType`、`InventoryButtonContext`、`InventoryPageState`、`InventoryPageSwitchWidget`
- 床 / 托盘 / 毒药 / 回放：`BedEffectRegistry`、`TrayEffectRegistry`、`CanSeePoison`、`ReplayRegistry`

### HarpyModLoader

- `src/main/java/org/agmas/harpymodloader/Harpymodloader.java`
- `src/main/java/org/agmas/harpymodloader/modded_murder/ModdedMurderGameMode.java`
- `src/main/java/org/agmas/harpymodloader/modifiers/HMLModifiers.java`
- `src/main/java/org/agmas/harpymodloader/modifiers/Modifier.java`
- `src/main/java/org/agmas/harpymodloader/events/ModdedRoleAssigned.java`
- `src/main/java/org/agmas/harpymodloader/events/ResetPlayerEvent.java`
- `src/main/java/org/agmas/harpymodloader/commands/*`

Harpy 当前按 `role.getFaction()` 分平民、义警、杀手、中立池；扩展职业应显式注册阵营，避免继续只靠 `isInnocent()` 和 `canUseKiller()` 推断。

### NoellesRoles

- `Noellesroles.java`：角色、词条、packet id、经济 API、事件注册。
- `NoellesRolesComponents.java`：CCA 组件。
- `ModItems.java`：物品注册。
- `NoellesRolesShops.java`、`shop/NoellesRolesShopBootstrap.java`：商店和多货币兼容。
- `roleassign/NoellesRolesRoleAssignedBootstrap.java`：分配后发物品和初始化状态。
- `combat/NoellesRolesCombatBootstrap.java`：枪击接管、左轮目标覆写、左轮反火和冷却 API 接入总入口。
- `death/NoellesRolesDeathBootstrap.java`：死亡保护、反噬和 DeathApi 分阶段击杀机制总入口。
- `record/NoellesRolesReplayFormatters.java`：回放格式化。
- `client/NoellesrolesClient.java`：客户端初始化。
- `client/hud/NoellesHudHandlers.java`、`NoellesHudSupport.java`：Wathe 通用 HUD API 的总注册入口和布局辅助。
- `client/roles/<role>/*StatusHud.java`：按职业拆分的普通屏幕 HUD provider。
- `client/appearance`、`client/instinct`、`client/visibility`、`client/inventory`、`client/ui`：外观、本能、隐藏物品、背包按钮和界面。

### StupidExpress

- `StupidExpress.java`
- `constants/SERoles.java`、`SEModifiers.java`、`SEItems.java`、`SEComponents.java`
- `shop/SEShops.java`、`shop/SEShopRegistry.java`
- `victory/StupidExpressVictoryRules.java`、`StupidExpressVictoryUtil.java`
- `role/*`、`modifier/*`
- `communication/StupidExpressCommunicationManager.java`
- `voice/StupidExpressVoiceChatPlugin.java`
- `record/StupidExpressReplay.java`、`StupidExpressReplayFormatters.java`

注意：StupidExpress 使用 Mojang 官方映射，例如 `ResourceLocation`、`Component`、`ServerPlayer`、`ItemStack#getDefaultInstance()`。

### kinssaba

- `KinsWathe.java`
- `KinsWatheRoles.java`
- `KinsWatheComponents.java`
- `KinsWatheItems.java`
- `KinsWatheShopBootstrap.java`、`KinsWatheShops.java`
- `victory/KinsWatheVictoryRules.java`
- `roles/*`、`mixin/*`、`client/hud/*`、`client/instinct/*`、`client/role_name/*`

kinssaba 使用 Yarn 命名，经济和任务收入集中在 `KinsWatheRoles.registerEconomyApi()`。

### StarryExpress

- `StarryExpress.java`
- `StarryExpressRoles.java`
- `StarryExpressShops.java`
- `StarryExpressItems.java`
- `client/instinct/*`
- `client/role_name/*`
- `client/visibility/*`

StarryExpress 使用 Mojang 官方映射；用户给出的 `...\src` 是源码目录，构建时要回到 `D:\哈比快车最新源码\starryexpress\StarryExpress1.3.2`。

## 映射命名差异

跨项目复制逻辑时必须先转换命名：

| Yarn / Wathe / Noelles / kinssaba | Mojang 官方映射 / Stupid / Starry |
| --- | --- |
| `Identifier` | `ResourceLocation` |
| `Text` | `Component` |
| `ServerPlayerEntity` | `ServerPlayer` |
| `PlayerEntity` | `Player` |
| `World` / `ServerWorld` | `Level` / `ServerLevel` |
| `getDefaultStack()` | `getDefaultInstance()` |
| `getWorld()` | `level()` |
| `getUuid()` | `getUUID()` |
| `getStackInHand(...)` | `getItemInHand(...)` |

不要直接把 StupidExpress / StarryExpress 的 Mojang 命名代码粘到 Wathe / Noelles / kinssaba，也不要反过来硬粘。

## 职业和阵营注册规则

新职业优先显式注册阵营：

```java
public static final Role MY_ROLE = WatheRoles.registerCivilianRole(new Role(
        MY_ROLE_ID,
        MyRoleConstants.ROLE_COLOR,
        true,
        false,
        Role.MoodType.REAL,
        WatheRoles.CIVILIAN.getMaxSprintTime(),
        false
));
```

常见语义：

- 平民阵营：`WatheRoles.registerCivilianRole(...)`
- 义警阵营：`WatheRoles.registerVigilanteRole(...)`
- 杀手阵营：`WatheRoles.registerKillerRole(...)`
- 中立阵营：`WatheRoles.registerNeutralRole(...)`

旧扩展里仍有不少 `WatheRoles.registerRole(...)`。如果本次任务不涉及阵营问题，不要为了“整理”大规模改；如果涉及胜利、RoleName 阵营色、左轮冷却、被动收入上限、Harpy 分配池，则应优先迁移到显式阵营。

Harpy 最大生成数使用：

```java
Harpymodloader.setRoleMaximum(MY_ROLE, 1);
```

修改 forceRole、中立替换、义警替换时重点检查：

- `Harpymodloader.OVERWRITE_ROLES`
- `Harpymodloader.ROLE_MAX`
- `ModdedMurderGameMode.assignVannilaRoles`
- `assignCivilianReplacingRoles`
- `assignVigilanteReplacingRoles`
- `assignKillerReplacingRoles`

## 商店和经济规则

商店统一优先使用 `ShopApi`：

- 完全替换某职业商店：`ShopApi.registerRoleShop(role, provider)`
- 静态职业商店：`ShopApi.registerStaticRoleShop(...)`
- 只追加 / 移除 / 替换默认商店条目：`ShopApi.registerShopModifier(id, priority, handler)`
- 通用购买逻辑：`ShopApi.defaultPurchase(context)`

扩展 provider 的 `purchase(...)` 只负责判断商品是否交付成功；不要自己扣钱、播放通用购买音效或写通用购买回放。扣钱、失败提示、音效和回放由 `PlayerShopComponent#tryBuy(...)` 统一做。

非杀手职业商店不要使用默认只适合杀手快捷栏的交付逻辑，优先用：

- `ShopEntry.giveToInventory(...)`
- `ShopEntry.directToHotbar(...)`
- `ShopEntry.action(...)`
- 或扩展自己的 `DirectGiveShopEntry` / provider 交付逻辑

多货币价格规则：

- 纯金币：`new ShopEntry(stack, int, type)` 或 `ShopPrice.money(int)`
- 多货币同时支付：`ShopPrice.allOf(CurrencyAmount.money(...), CurrencyAmount.taskMoney(...))`
- 多方案任选：`ShopPrice.anyOf(ShopPrice.option(...), ShopPrice.option(...))`
- 读取默认第 0 组金币：`ShopApi.getDefaultMoneyPrice(item, 0, fallback)`
- 读取某组某货币：`ShopApi.getDefaultCurrencyPrice(item, optionIndex, currencyId, fallback)`
- 只有明确要完整继承默认价格结构时，才使用 `ShopApi.getDefaultShopPrice(item)`

中立 / 平民职业通常只能读默认商品的金币价格，不要完整复制默认杀手 `ShopPrice`，否则后续 Wathe 重新启用任务币时可能导致该职业买不起。

当前任务币状态：

- `EconomyApi.TASK_MONEY`、`CurrencyAmount.taskMoney(...)` 和 `/wathe:setTaskMoney` 相关能力保留。
- 默认杀手商店当前恢复为纯金币。
- `GameConstants.TASK_MONEY_PER_KILLER_TASK = 0`
- `GameConstants.TASK_MONEY_PER_KILL = 0`
- `EconomyApi` 中任务币 HUD 注册逻辑目前注释暂停。

被动收入：

- Wathe 默认杀手能力角色拥有被动收入。
- 非杀手职业要被动收入：`EconomyApi.registerPassiveIncomeRole(s)`
- 动态允许 / 禁止：`EconomyApi.registerPassiveIncomeRule(...)`
- 修改数值：`EconomyApi.registerPassiveIncomeModifier(...)`
- 最终上限由 `GameConstants.getPassiveMoneyAmount(faction, currentBalance, baseIncome)` 统一裁剪。

任务收入：

- 新增心情任务优先用 `MoodTaskApi.registerTask(...)` 注册 `MoodTaskDefinition`，不要继续往 `PlayerMoodComponent.Task` 追加 enum；旧 enum 只作为 Wathe 内置任务和旧扩展兼容层保留。
- 内置任务默认进入随机池；扩展任务默认只允许指定发放，只有明确希望进入普通随机任务池时才在定义里显式启用随机。
- 指定发任务用 `MoodTaskApi.assignTask(player, taskId)`；随机发放和补槽继续用 `assignRandomTasks(...)` / `fillRandomTaskSlots(...)`。
- 单纯删除任务用 `MoodTaskApi.removeTask(...)`；按完成流程完成任务用 `MoodTaskApi.completeTask(...)`，后者会加心情、写回放、触发任务完成 API。
- 阻止特殊状态完成任务，例如附身/控制/失控，用 `MoodTaskApi.registerCompletionRule(...)`，不要再 mixin `PlayerMoodComponent#completeTask(...)`。
- 新任务点透视类型用 `MoodTaskPointApi.registerTaskPoint(...)`，地图扫描追加用 `MoodTaskPointApi.registerScanHandler(...)`，任务定义里绑定对应任务点 id。
- 普通任务完成监听：`TaskCompletionApi.AFTER_TASK_COMPLETE`
- 旧金币收入兼容：`TaskCompletionApi.registerTaskIncomeProvider(...)`
- “任务完成但跳过 Wathe 默认收入”的特殊场景用 `TaskCompletionApi.registerTaskIncomeRule(...)` 返回 `SUPPRESS_DEFAULT_INCOME`，不要再 mixin `TaskCompletionApi.handleTaskCompleted(...)`。
- 拥有杀手能力的玩家不会叠加旧任务金币 provider；Taskmaster 这类“杀手完成任务也额外给金币”的设计，应像 kinssaba 一样用 `AFTER_TASK_COMPLETE` 单独补发。

集合去重坑：

- 给 `registerTaskIncomeProvider` 或类似职业名单补职业时，不要用 `Set.of(...)` 容纳可能重复的 `Role`。
- `Set.of(...)` 遇到重复元素会在 main entrypoint 初始化时抛 `IllegalArgumentException: duplicate element`，服务器直接启动失败。
- 需要去重时用 `new LinkedHashSet<>(List.of(...))`，并在中文注释里说明原因。

## 外观、尸体和 RoleName HUD

接口公开化需求优先检查这些 API，避免扩展继续各自 mixin 渲染器：

- 玩家外观：`PlayerAppearanceApi.registerPlayerSkin(...)`
- 客户端尸体皮肤覆盖：`PlayerAppearanceApi.registerBodySkin(...)`
- 服务端生成尸体外观 UUID：`BodyAppearanceApi.register(...)`
- 准心名字：`RoleNameHudApi.registerName(...)`
- 准心射线来源：`RoleNameHudApi.registerRaycastSource(...)`
- 准心目标过滤：`RoleNameHudApi.registerPlayerTargetFilter(...)`
- 同伙状态：`RoleNameHudApi.registerCohortState(...)`
- 目标单向显示为同伙：`RoleNameHudApi.registerCohortTargetState(...)`
- 非玩家实体名牌：`RoleNameHudApi.registerEntityName(...)`
- 隐藏同伙提示：`RoleNameHudApi.registerCohortHint(...)`
- 准心额外 HUD：`RoleNameHudApi.registerExtraHud(...)`
- 玩家 / 尸体隐藏、不可准心选中、不可道具交互：`TargetVisibilityApi.registerPlayerRule(...)`、`TargetVisibilityApi.registerBodyRule(...)`

`registerEntityName(...)` 只给魔术师播放体这类“非玩家实体也要在准心处显示名字”的窄场景使用；普通玩家名仍用 `registerName(...)`，避免绕开同伙提示、精神错乱混淆和玩家目标过滤。

`TargetVisibilityApi` 面向“某个观察者是否能看见 / 选中 / 交互某个玩家或玩家尸体”。它会接入 Wathe 的玩家和尸体渲染、`LivingEntity#canHit` 客户端选中、RoleNameHud 尸体射线、默认刀枪准心目标、尸袋、匕首 / 枪击服务端命中和本能描边。扩展隐藏尸体或隐藏玩家时优先注册 API 规则，不要再 mixin `PlayerBodyEntityRenderer`、`LivingEntity#canHit`、`RoleNameRenderer` 或 `CrosshairRenderer`；服务端职业能力和物品仍必须在自己的 handler 里调用 `canInteractWithBody(...)`、`canInteractWithPlayer(...)`、`canAttackPlayer(...)` 重新校验。

外观优先级经验：

- priority 越大越先执行。
- handler 返回 `null` / `PASS` 表示让低优先级继续处理。
- 召集者这类全局限时伪装应高于普通主动变形。
- 灵术师出窍这种“只影响自己客户端看到的一切”的视觉覆盖应走客户端 handler，并注意不改变服务端真实身份 / 尸体 owner。
- 双重人格这类低优先级外观应给主动变形让路。
- `PlayerAppearanceApi.resolveOriginalSkinTextures(...)` 用于防止伪装套娃，不要读取目标实体当前已经被覆盖过的皮肤再二次套用。

## 疯魔模式 API

扩展职业要接入疯魔机制时，优先使用 `dev.doctor4t.wathe.api.psycho`，不要再 mixin `PlayerPsychoComponent`、球棒命中音效、`psycho_drone` 背景音或疯魔皮肤渲染器。

- 基础定义：用 `PsychoModeProfile.copyOf(PsychoModeApi.createDefaultProfile(), id)` 继承 Wathe 默认疯魔，再覆盖 `durationTicks`、`armour`、`grantedItems`、`hitSound`、`shieldSound`、`backgroundSound(sound, play)`、`visualSettings`、`nameTranslationKey` 等字段。
- 启动/结束：服务端用 `PsychoModeApi.start(player, profileId)` 启动；强制收束、回合清理、召集等场景用 `PsychoModeApi.stop(player, recordReplay)`，不要直接改 `psychoTicks/armour`。
- 查询状态：用 `PsychoModeApi.isActive(player)`、`isActive(player, profileId)`、`getActiveProfile(player)`、`getRemainingTicks(player)`、`getArmour(player)`，避免扩展依赖 CCA 内部字段。
- 授予物品：profile 的 `grantedItems` 会被 Wathe 打 `PSYCHO_GRANTED_PROFILE` 标记，结束时只回收该 profile 授予的临时物品，避免误删玩家自己持有的同类物品。
- 锁栏/防丢弃：默认锁 profile 授予物品；特殊武器可用 `lockedItemPredicate` / `meleeWeaponPredicate` 定义，不要再写 `ServerPlayNetworkHandler`、`PlayerInventory`、`MinecraftClient` 的锁槽 mixin。
- 护盾交互：穿盾/强制挡盾用 `PsychoModeApi.registerShieldRule(id, priority, handler)`，handler 读取 `PsychoShieldContext` 并返回 `BYPASS`、`BLOCK` 或 `PASS`。
- 声音与音乐：球棒/近战命中声音走 profile 的 `hitSound`，护盾声音走 `shieldSound`，背景音乐走 `backgroundSound` 和 `playBackgroundSound`；自定义背景音乐还要在客户端初始化调用 `PsychoModeClientApi.registerBackgroundAmbience(sound, intervalTicks)`。
- 皮肤：profile 可给默认 `PsychoVisualSettings.skin(...)`；扩展需要覆盖时在客户端用 `PsychoModeClientApi.registerVisualProvider(id, priority, provider)`。这是“profile 默认皮肤 + 扩展按优先级覆盖”的双层机制。
- Mood 显示：自定义疯魔完整/破损图标、跑马文本、文本颜色和倒计时条颜色用 `MoodHudApi.registerPsychoStyle(...)` 返回 `PsychoMoodHudStyle`；Wathe 统一按当前护盾是否大于 0 切换 body / hitBody，0 护盾 profile 启动后应直接显示破损图标。颜色 provider 可返回 `0xRRGGBB` 或 `0xAARRGGBB`，渲染器会重新写入 HUD 需要的 alpha。
- 回放名称：疯魔结束回放使用 `PsychoModeApi.REPLAY_MODE_NAME_KEY` 中的翻译 key，默认名称是 `psycho_mode.wathe.default`；疯魔护盾抵挡回放使用 `PsychoModeApi.REPLAY_SHIELD_NAME_KEY`，默认名称是 `psycho_shield.wathe.default`。扩展 profile 要给自己的 `psycho_mode.<modid>.<name>` / `psycho_shield.<modid>.<name>` 翻译，`replay.global.wathe.psycho_mode_end` 会显示成“%s的%s结束”，护盾抵挡会显示成“%s 的%s抵挡……”。
- 扩展代码组织：NoellesRoles、kinssaba 等扩展要按职业拆到 `roles/<role>/...PsychoHandler`，聚合 bootstrap 只调用各职业 `init()`，不要把所有疯魔规则塞进一个公共大类。

## 本能、HUD、手持物隐藏

本能透视：

- 是否能开本能：`InstinctApi.registerAvailability(...)`
- 某目标颜色 / 隐藏：`InstinctApi.registerHighlight(...)`
- 返回 `PASS` 继续，`ENABLE` / `DISABLE` 或 `color(...)` / `hide()` 结束。
- 全局压制本能的效果应使用更高 priority 返回 `DISABLE`。

心情 HUD：

- 职业固定样式：`MoodHudApi.registerRoleStyle(role, style)`
- 临时覆盖：`MoodHudApi.registerMoodProvider(...)`
- 疯魔样式：`MoodHudApi.registerPsychoStyle(...)`

时间 HUD：

- 顶部时间替换 / 隐藏：`TimeHudApi.registerProvider(...)`
- 固定色特殊倒计时用 `TimeDisplay.showFixedColor(...)`
- 普通动态倒计时用 `showCountdown(...)` 或 `showDynamic(...)`

通用屏幕 HUD：

- 普通右下角职业状态用 `HudOverlayApi.registerAliveRole(...)`，它会同时判断本地玩家职业和 Wathe 的 `GameFunctions.isPlayerAliveAndSurvival(...)` 存活定义。
- 非职业专属但仍只应给活人看的 HUD，例如被控制、被绑架、开局安全、停电提示、狙击镜遮罩，用 `HudOverlayApi.register(...)`，并在 provider 内显式检查 `context.aliveAndSurvival()`。
- `HudOverlayLayer.BEFORE_HUD` 适合尽早盖住画面的提示；`MAIN_HUD` 适合常规状态文字；`AFTER_HUD` 适合压在最上层的遮罩，需要复画快捷栏时调用 `context.renderHotbar()`。
- 同一 layer 内 priority 越大越晚渲染，因此会盖住低 priority 的 HUD。
- 扩展侧按职业和词条拆文件，聚合类只负责调用各 `register()`；不要把所有职业 HUD 塞进一个大类。
- 普通 HUD 不要再新增 `InGameHud` / `RoleNameRenderer` mixin；如果 API 已覆盖旧 mixin，要同步删除源码文件和 mixin json 条目。

准心图标 HUD：

- 替换屏幕中心 3x3 准心或准心下方 10x7 小图标时用 `CrosshairHudApi.registerProvider(...)`，不要再 mixin `CrosshairRenderer`。
- 只在默认准心后额外补进度条时用 `CrosshairHudApi.registerOverlay(...)`，例如时停者怀表冷却/蓄力条。
- Provider 返回 `PASS` 表示继续交给后续 provider 或 Wathe 默认准心；返回 `HANDLED` 表示本帧已处理完，包含狙击枪开镜这种“故意隐藏默认准心”的情况。
- 扩展侧按职业或词条拆文件，聚合类只调用各 `register()`；准心只是客户端提示，服务端仍必须重新校验职业、存活、冷却、距离和目标合法性。

手持物隐藏：

- 某职业某物品隐藏：`HeldItemInvisibilityApi.registerHiddenItem(role, item)`
- 多物品：`registerHiddenItems(...)`
- 动态状态隐藏：`registerRule(...)`
- API 已处理“本人 F5 看自己仍可见、死亡 / 旁观 / 创造可见、只隐藏局内存活玩家视角”的边界，不要在扩展里重复写整套渲染 mixin。

## 背包按钮和分页

扩展要在背包里加按钮时，优先接 `dev.doctor4t.wathe.api.client.inventory`，不要再新增 `LimitedInventoryScreen` / `InventoryScreen` / `CreativeInventoryScreen` 的 screen mixin。

- `InventoryScreenType.LIMITED`：Wathe 局内限制背包，职业选人按钮通常只挂这里。
- `InventoryScreenType.VANILLA`：原版玩家背包，适合图鉴、帮助入口等非职业状态按钮。
- `InventoryScreenType.CREATIVE`：创造背包，适合 StarryExpress 图鉴这类创造模式也要显示的入口。
- 扩展在客户端初始化时用 `InventoryButtonApi.registerProvider(id, priority, provider)` 注册。provider 返回 `null` 表示当前 screen 不需要按钮。
- `InventoryButtonContext.addWidget(groupId, widget)` 会把 widget 加入命名分组；动态列表用 `replaceGroup` / `clearGroup` / `setGroupVisible`，不要直接操作 screen 内部 children。
- “删除 widget”在 API 里表现为隐藏、禁用和解除焦点，目的是兼容 MC screen 内部列表没有稳定公开删除接口的情况。
- 需要 NoellesRoles 同款换页时使用 `InventoryButtonLayout`、`InventoryPageState`、`InventoryPageSwitchWidget`。Wathe 会在开局、停局、结算完成、断线时统一 reset 页码。
- 输入框或二阶段选择期间禁止 E 键关背包时，在 `InventoryButtonExtension.allowInventoryKeyClose(...)` 返回 `false`，关闭时清理静态输入状态放进 `close(...)`。
- 变形怪这类“点击后头像和翻页按钮都要消失”的场景，应让头像和翻页按钮共享同一个可见性条件或同一个 group，避免只隐藏一部分按钮。

## 胜利、死亡和回放

特殊胜利优先接入 `VictoryApi`：

- 不参与：`VictoryApi.VictoryResult.pass()`
- 拖住普通结算：`VictoryApi.VictoryResult.keepRunning()`
- 普通阵营共胜：`VictoryApi.VictoryResult.vanillaWin(...)`
- 独立胜利：`VictoryApi.VictoryResult.customWin(...)`
- 主动立即结束：`VictoryApi.endGameWithCustomVictory(...)`

新增独立胜利必须明确：

- 只剩自己是否独胜；
- 是否允许杀手 / 乘客共胜；
- 是否需要拖住 `TIME` 超时结算；
- 死亡队友 / 伴侣是否也要写进胜利阵营；
- 结算页颜色和翻译 key。

枪击接管、左轮误伤惩罚、枪械客户端目标覆写和开火后冷却修正优先接 `dev.doctor4t.wathe.api.combat.GunShotApi`。扩展侧要按职业或词条拆 handler，再由聚合 bootstrap 注册；不要再 mixin `GunShootPayload`、`RevolverItem` 或 `DerringerItem` 的通用流程。

死亡保护优先接 `AllowPlayerDeath` 或各扩展已有死亡引导器；击杀奖励、重复死亡吞噬、致死确认前转化、确认死亡后清理、心情重置前处理和尸体生成回调优先接 `dev.doctor4t.wathe.api.death.DeathApi`，不要把大量死亡特判堆到主类或 mixin `GameFunctions.killPlayer(...)`。

`DeathApi` 优先级约定：重复死亡保护最高，其次是特殊存活保护、死亡流程状态、回放上下文、致死确认前拦截、普通逻辑、确认死亡后的奖励 / 二段机制、最终清理。priority 越大越先执行；同 priority 后注册的规则先执行。

需要新死因或技能事件时同时补：

- 事件 id / death reason id；
- `GameRecordManager` 记录；
- `ReplayRegistry` 格式器；
- `zh_cn.json` / `en_us.json` 翻译；
- 回合结束和玩家重置清理。

回放数据尽量存稳定 id、UUID 和必要显示名兜底；展示文字用 `Text.translatable(...)` / `Component.translatable(...)`，不要把中文句子硬编码进记录数据。

## CCA、网络包和客户端分层

需要跨 tick、死亡、掉线、回合重置、客户端显示的状态，优先建 CCA 组件：

- 玩家状态：entity component
- 世界共享状态：world component
- 跨维度 / 全服唯一状态：scoreboard component
- 只给本人显示的状态用 `shouldSyncWith` 限制同步范围
- 所有人可见的标记 / 外观 / HUD 状态可同步给所有人，再由客户端 handler 判断观看者身份

网络包规则：

- C2S 包只表达玩家意图，服务端必须重新校验职业、存活、冷却、距离、目标合法性和对局状态。
- S2C 包只同步客户端渲染所需状态，不要让客户端决定服务端玩法结果。
- 新增 packet 要补 payload codec、注册入口、接收器和初始化调用顺序。

客户端代码：

- HUD、屏幕、相机、模型谓词、按键、渲染混入放 `src/client/java`；普通屏幕 HUD 优先接 `HudOverlayApi`，准心图标优先接 `CrosshairHudApi`，准心名字 / 目标文字优先接 `RoleNameHudApi`，只有 API 不够表达时才保留窄 mixin。
- 服务端逻辑、物品行为、死亡、任务、胜利、组件注册放 `src/main/java`。
- client mixin 注册到 `*.client.mixins.json`，common/server mixin 注册到主 mixin json。

## 常见需求类型处理模板

### Wathe 接口公开化

1. 找出现有扩展的重复 mixin / helper。
2. 在 Wathe 设计一个窄 API：注册 handler、priority、PASS 语义、上下文 record；背包按钮类需求优先考虑 `InventoryButtonApi` 的 screen type、group 和 lifecycle，普通屏幕 HUD 优先考虑 `HudOverlayApi` 的 layer 和存活过滤。
3. 疯魔类需求优先抽成 `PsychoModeApi` profile / shield rule / client visual provider；持续时间、护盾、授予物品、锁栏、声音、背景音乐、皮肤、结束回放名称都应由 profile 或对应 handler 表达。
4. Wathe 原逻辑改成先询问 API，再走默认行为。
5. 把 NoellesRoles / StupidExpress / kinssaba / StarryExpress 中相关逻辑接入 API。
6. 删除或停用已不需要的扩展 mixin，并同步 mixin json。
7. 如果新增或调整了 API，同步更新 Wathe / 扩展 README 与 AGENTS，避免后续维护继续按旧 mixin 路线开发。
8. 编译 Wathe，复制 jar，再编译受影响扩展。

### 商店 / 经济改动

1. 先读 `README_SHOP_CURRENCY_API.md`、`ShopApi`、`EconomyApi`、`PlayerShopComponent`、`StoreRenderer`。
2. 明确是改默认杀手商店、职业专属商店、货币 HUD、任务收益、击杀收益还是被动收入。
3. 判断扩展是否应完整继承价格，还是只读某个 option 的某种货币。
4. 价格、收入、HUD 坐标和阈值放常量。
5. 渲染改动必须关注背景框、居中、多行向上扩展、余额到 0 的过渡动画。

### 新职业 / 新词条

1. 明确中文名、英文 id、阵营、职业色、MoodType、是否能看时间、最大冲刺时间。
2. 注册角色 / 词条和 Harpy 最大数。
3. 需要状态就建组件，需要交互就建 packet，需要商店就接 `ShopApi`。
4. 补开局分配、重置、回合结束清理、死亡保护、胜利规则、回放、语言文件和客户端表现。
5. 编译并检查服务器启动风险，尤其是集合重复、mixin 注入点和映射命名。

### bug 修复

1. 记录现象、复现步骤、期望结果。
2. 用 `rg` 找入口和最近改动，不凭名称猜。
3. 先判断是服务端逻辑、客户端显示、同步、配置、mixin 注入点还是依赖 jar 过旧。
4. 修复后至少编译直接受影响项目；跨 Wathe API 的修复要做完整 jar 传递和扩展编译。

## 编译和 jar 传递顺序

只改 Wathe：

```powershell
cd "D:\哈比快车最新源码\wathe\Wathe - 副本1"
.\gradlew.bat build
```

Wathe 改动会影响扩展时，复制输出 jar：

- 到 Harpy：`D:\哈比快车最新源码\harpymodloader\HarpyModLoader1\libs`
- 到 NoellesRoles：`D:\哈比快车最新源码\noellesroles\NoellesRoles - 副本 - 副本 - 副本5.7.1\libs`
- 到 StupidExpress：`D:\哈比快车最新源码\stupidexpress\StupidExpress2.1\libs`
- 到 kinssaba：`D:\哈比快车最新源码\kinswathe\kinssaba\libs`
- 到 StarryExpress：`D:\哈比快车最新源码\starryexpress\StarryExpress1.3.2\libs`

Harpy 的 `build.gradle` 使用固定文件名：

```text
libs/wathe-${tmm_version}.jar
```

当前 `tmm_version=1.3.3-1.21.1`，所以 Harpy 侧要确保存在：

```text
libs/wathe-1.3.3-1.21.1.jar
```

如果 Wathe 改了 Harpy 需要的 API，顺序是：

```powershell
cd "D:\哈比快车最新源码\wathe\Wathe - 副本1"
.\gradlew.bat build

cd "D:\哈比快车最新源码\harpymodloader\HarpyModLoader1"
.\gradlew.bat build
```

然后把新的 `harpymodloader-*.jar` 放进 NoellesRoles、kinssaba、StarryExpress 等需要本地 Harpy 的 `libs`。

扩展编译：

```powershell
cd "D:\哈比快车最新源码\noellesroles\NoellesRoles - 副本 - 副本 - 副本5.7.1"
.\gradlew.bat build

cd "D:\哈比快车最新源码\stupidexpress\StupidExpress2.1"
gradle build

cd "D:\哈比快车最新源码\kinswathe\kinssaba"
.\gradlew.bat build

cd "D:\哈比快车最新源码\starryexpress\StarryExpress1.3.2"
.\gradlew.bat build
```

StupidExpress 当前按用户历史要求用本机 `gradle build`。StarryExpress 构建目录是父目录，不是 `src`。

## 后续提示词简化格式

用户后续可以少写路径，直接写：

```text
请按 Wathe 根目录 AGENTS.md 的流程处理。
任务类型：接口公开化 / Wathe 功能 / 扩展职业 / bug 修复 / 商店经济 / 胜利结算 / HUD 外观
目标工程：Wathe / Harpy / NoellesRoles / StupidExpress / kinssaba / StarryExpress / 多工程联调
需求：
复现或现状：
期望：
参考文件或参考职业：
是否先只给方案：
是否允许修改扩展：
编译要求：
中文注释：需要详细中文注释
```

如果用户没写“先只给方案”，默认应在读完源码后直接实现、编译并汇报结果。
