# Wathe 门组 API

Wathe 的小门方块实体只存在于下半格。地图中的双门则由两个独立的小门实体组成，但玩法状态必须作为一个门组统一修改。扩展 Mod 应使用 `dev.doctor4t.wathe.api.door.DoorApi`，不要再分别调用 `setBlasted`、`setJammed`、`setCloseCountdown` 和 `toggle` 拼装门技能。

## 为什么需要门组事务

旧逻辑用“同一个世界 tick 只允许切换一次”避免双门递归。这会吞掉同 tick 内真实发生的第二次交互：关门后立即撬门时，开门动作被拦截，但 `blasted=true` 仍会写入，最终得到永久关闭的损坏门。双门也只联动 `OPEN`，没有联动损坏和锁定状态，修复后容易形成一开一关。

现在所有高层操作都直接写明确目标状态，并一次处理整个门组：

- 普通切换严格按服务端收到的顺序执行，不再用 tick 时间戳吞交互；
- `pryOpen` 永久打开整个门组；
- `jamClosed` 关闭整个门组并刷新锁定时间；
- `repair` 清除整组损坏与锁定，并由调用方选择修复后关闭或打开后自动关闭；
- 任意一侧被永久撬开时，整个门组都按永久撬开处理，临时锁门不能把它重新关上。
- 服务端 tick 会自动修复旧存档遗留的“关闭但 blasted”或只有一侧 blasted：整组恢复为永久打开，不要求管理员手动重置地图。

## 常用入口

```java
DoorBlockEntity door = DoorApi.findDoor(world, clickedPos);
if (door == null) {
    return ActionResult.PASS;
}

DoorApi.OperationResult pry = DoorApi.pryOpen(door);
DoorApi.OperationResult jam = DoorApi.jamClosed(door, 20 * 30);
DoorApi.OperationResult closeRepair = DoorApi.repair(door, DoorApi.RepairMode.CLOSE);
DoorApi.OperationResult openRepair = DoorApi.repair(door, DoorApi.RepairMode.OPEN_THEN_AUTOCLOSE);
```

`DoorApi.inspect(door)` 聚合整个门组状态。`OperationResult` 提供操作是否落地、是否改变状态、受影响门扇数以及操作前是否损坏/锁定，扩展可据此决定冷却、音效和回放。
需要保留扩展自己的修理音而隐藏 Wathe 普通门声时，使用三参数 `repair(door, mode, true)`。

## 修复策略

- `RepairMode.CLOSE`：整组修复后立即关闭。NoellesRoles 工具箱和巫妖使用该策略。
- `RepairMode.OPEN_THEN_AUTOCLOSE`：整组修复后保持打开，并重新开始 `GameConstants.DOOR_AUTOCLOSE_TIME`。kinssaba 技术员扳手使用该策略。

## 服务端边界

门状态、物品冷却和回放必须只在服务端落地。客户端可以先通过同步状态判断是否显示成功交互，但不能自行写门实体字段。`DoorBlockEntity#blast()`、`jam()` 和 `toggle()` 为旧扩展保留，并已委托给 `DoorApi`；新代码应直接调用 API。底层 direct setter 只用于 DoorApi、NBT 恢复和旧兼容层。

## 回归检查

修改门相关能力时至少检查：单门和双门、左右门扇、上下半格、关门与撬门同 tick、开门与锁门同 tick、五秒自动关门、损坏门修复、按钮开门、列车行驶锁门、时停回溯、区块重载和地图重置。
