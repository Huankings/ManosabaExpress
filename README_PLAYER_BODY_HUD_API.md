# Player Body Info / HUD API

Wathe 统一保存和显示玩家尸体的验尸信息。扩展不应再为 `PlayerBodyEntity` 重复建立
`deathReason`、`playerRole` 或凶手组件，也不应在 `RoleNameRenderer` 附近用固定坐标自行绘制尸体文字。

## 服务端尸体快照

真实死亡由 `GameFunctions.killPlayer(...)` 自动调用 `PlayerBodyInfoApi.initializeFromDeath(...)`，保存：

- 真实死者 UUID 与死亡时名字；
- 死因 ID 与死亡时职业 ID；
- 凶手 UUID、名字和职业；
- 是否为人工伪尸体；
- 可随尸体 NBT 和时停回溯恢复的死亡经过 tick。

扩展主动创建尸体时，在 `spawnEntity(...)` 前调用：

```java
PlayerBodyInfoApi.initializeSynthetic(body, new PlayerBodyInfoSnapshot(
        target.getUuid(),
        target.getGameProfile().getName(),
        deathReason,
        fakeRole.identifier(),
        null,
        null,
        null,
        true,
        target.getUuid()
));
```

`modifierSourceUuid` 只表示 Harpy 应参考谁的本局词条数量；它不会改变尸体真实 owner、名字或职业。
伪尸体默认凶手未知。扩展如需补充自己的快照，使用
`PlayerBodyInfoApi.registerInitializedHandler(...)`，不要 mixin `GameFunctions.killPlayer(...)`。

## 客户端五行 HUD

`PlayerBodyHudApi` 固定定义：

1. `NAME`：玩家名；
2. `DEATH`：死亡时间与死因；
3. `IDENTITY`：职业和词条；
4. `AFFILIATION`：阵营；
5. `KILLER`：凶手与当前状态。

可见性使用 `registerLineRule(...)`，返回 `PASS / SHOW / HIDE / OBFUSCATE`。内容覆盖使用
`registerLineProvider(...)`；凶手特殊状态使用 `registerKillerStatusProvider(...)`；职业交互提示使用
`registerAdditionalLines(...)`。固定行和附加行均由 Wathe 连续排版，扩展不得再手算 y 坐标。

默认只有 `GameFunctions.isPlayerSpectatingOrCreative(...)` 的非存活观察者能看到五行完整信息。
存活玩家看不到尸体玩家名，避免通过名字识破尸体外观伪装。扩展职业可单独开放其它行，但不应顺带开放名字。

## 凶手状态

默认状态先检查玩家是否在线，再按 Wathe 玩法生命状态判断：

- `ALIVE`：`isPlayerAliveAndSurvival(...)`；
- `DEAD`：普通非存活玩家，或 `PlayerLifeStateApi.isForensicallyDead(...)`；
- `OFFLINE`：不在当前 PlayerList。

时停者时间狭缝应同时保持玩法存活授权并设置 `setForensicallyDead(player, true)`；双重人格等真正的
特殊存活旁观只设置玩法存活授权，不设置验尸死亡标记。

## 回溯与网络

死亡时间采用“同步基准值 + 客户端本地递增”，正常 tick 不发送网络包。尸体 NBT 保存当前经过 tick；
时停者恢复历史尸体 NBT 后只同步新基准，HUD 时间和尸体分解进度会一起回退。

Harpy 的词条快照和扩展的尸体吞噬状态同样必须写入尸体 NBT，才能随时停者尸体快照恢复。

## 观察者尸体透视颜色覆盖

`ObserverRoleDisplayApi.registerBodyColor(id, priority, handler)` 为非存活观察者提供独立的透视色覆盖。返回 RGB 结束解析，返回 null 继续后续规则，负值表示隐藏描边。上下文包含尸体和原有默认色，原尸体的验尸资料可通过 `context.body()` 查询。

NoellesRoles 灵媒选择在接受申请之前读取灵魂的真实分配职业颜色，将这个时点的 RGB 写入专用尸体组件。玩家死后通过 `/setRole` 改职业，再接受召唤时，透视颜色会保存新职业色。验尸 HUD 继续读取 `PlayerBodyInfoApi` 保存的死亡时职业，因而两者允许显示不同职业色。

此显示组件随尸体 NBT 捕获，时停回溯的最终帧会补发恢复后的组件状态。人工伪尸体继续使用自身规则。
