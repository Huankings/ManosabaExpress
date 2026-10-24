# Wathe 疯魔模式 API

扩展职业应通过 `dev.doctor4t.wathe.api.psycho` 注册和控制疯魔，不要直接修改
`PlayerPsychoComponent#psychoTicks`、`armour` 或 `GameWorldComponent#psychosActive`。

## 注册 profile

```java
PsychoModeProfile profile = PsychoModeProfile.copyOf(
        PsychoModeApi.createDefaultProfile(),
        MyMod.id("my_psycho")
)
        .durationTicks(20 * 45)
        .armour(1)
        .cooldownItem(ModItems.MY_PSYCHO_ICON)
        .grantedItems(List.of(ModItems.MY_WEAPON.getDefaultStack()))
        .build();
PsychoModeApi.registerProfile(profile);
```

`cooldownItem` 是可选元数据，只说明该 profile 对应哪个商店图标/冷却物品。API 不会自动决定普通购买冷却；
跨职业压制机制可以调用 `PsychoModeApi.ensureMinimumCooldown`，在不缩短已有长冷却的前提下托底冷却。

Wathe 默认疯魔已经绑定 `WatheItems.PSYCHO_MODE`。扩展自定义疯魔如果使用自己的购买图标，应显式设置
`cooldownItem`；完全没有物品冷却的自动触发型疯魔可以保持 `null`。

## 启动、结束、加时和增加护盾

- `PsychoModeApi.start(player, profileId)`：启动 profile。
- `PsychoModeApi.stop(player, recordReplay)`：真正结束疯魔；按 profile 来源标记回收临时物品。
- `PsychoModeApi.addRemainingTicks(player, profileId, ticks)`：给当前指定 profile 加时，同时扩大 HUD 最大时间，允许超过初始持续时间。
- `PsychoModeApi.addArmourLayers(player, profileId, layers)`：给当前指定 profile 增加护盾层数并同步客户端。
- `PsychoModeApi.ensureMinimumCooldown(player, profileId, ticks)`：把 profile 对应物品的剩余冷却托底到指定时间。
- `PsychoModeApi.registeredProfiles()`：返回当前所有已注册 profile 的只读快照，供跨扩展机制读取 `cooldownItem` 等公开元数据。
- `PsychoModeApi.clearRegisteredProfileCooldowns(player)`：清除所有已注册 profile 的非空 `cooldownItem` 冷却；多个 profile 共用同一图标时只清一次。

加时和增加护盾入口都要求玩家当前仍处于给定 profile，避免延迟事件把时间或护盾加到另一种疯魔上；
增加护盾只接受正数层数，并会在整数上限处饱和。

需要实现黑客、全场事件等“清理所有疯魔图标冷却”的机制时，应调用
`clearRegisteredProfileCooldowns`，不要硬编码 Wathe 原生图标或其它扩展的物品字段。这样后续扩展只要在
profile 中登记 `cooldownItem`，就会自动进入统一清理范围。

## 暂停和恢复同一次疯魔

更高优先级的全场时刻需要暂时撤销疯魔武器、音乐和实际机制时，使用：

```java
PsychoModeState state = PsychoModeApi.pause(player);
// 把 state 存进自己的同步组件 / 时间线组件。

boolean resumed = PsychoModeApi.resume(player, state);
```

暂停具有以下语义：

- 精确回收带当前 profile 来源标记的临时物品；
- 从活跃疯魔计数中移除；
- 不记录疯魔结束回放；
- 返回 profile、剩余时间、HUD 最大时间、当前护盾和初始护盾快照。

恢复会重新走统一物品授予和锁栏流程，再恢复快照时间与护盾。若快捷栏空间不足，恢复返回 `false`，
调用方应保留快照并在后续安全时机重试。暂停快照不包含背包、物品冷却或扩展职业组件；这些仍由调用方管理。

如果状态不会恢复，应改用 `stop` 真正结束，不要把 `pause` 当作无回放结束接口。

## 时停与同步

`PlayerPsychoComponent` 是局内时间线状态。实现回溯的扩展应同时快照该组件和自己保存的
`PsychoModeState`；最终历史帧落地后再按特殊时刻优先级执行一次 reconcile，确保临时物品、音乐和暂停标记一致。

修改本 API 后，必须先构建 Wathe，把最新非 sources jar 放入依赖扩展的 `libs`，再编译扩展。
