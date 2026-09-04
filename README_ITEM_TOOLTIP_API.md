# Wathe 物品 Tooltip API

`ItemTooltipApi` 是 Wathe 的客户端物品描述与原版物品冷却读秒入口。扩展不需要再注册
`ItemTooltipCallback`，也不需要用默认总时长乘 `getCooldownProgress()` 猜测剩余时间。

## 标准描述与冷却

在扩展的客户端初始化入口注册物品：

```java
ItemTooltipApi.registerItems(
        ModItems.MY_WEAPON,
        ModItems.MY_TOOL
);
```

API 会完成两件事：

1. 读取物品的 `item.<modid>.<path>.tooltip` 翻译，并按翻译文本中的 `\n` 拆成多行灰色描述。
2. 如果本地玩家的 `ItemCooldownManager` 中存在该物品的活动条目，在描述前添加红色
   `tip.cooldown` 读秒。

冷却读秒直接使用当前条目的 `endTick - tick`。因此同一个物品的开局冷却、普通使用冷却、
GunShotApi 修正冷却和临时冷却可以拥有不同总时长，不需要扩展同步“当前冷却来源”。
`GameConstants.ITEM_COOLDOWNS` 仍用于服务端默认玩法数值，不应再用于客户端 tooltip 换算。

## 动态附加文本

物品需要展示数据组件或职业组件状态时，注册 appender：

```java
ItemTooltipApi.registerAppender(
        MyMod.id("my_weapon_status"),
        ItemTooltipApi.DEFAULT_PRIORITY,
        ModItems.MY_WEAPON,
        context -> context.tooltip().add(Text.translatable("item.mymod.my_weapon.status"))
);
```

Appender 在标准冷却和描述后执行，priority 越小越先追加，同 priority 下先注册者先执行。
`Context` 提供客户端、本地玩家、物品栈、tooltip context/type 和最终文本列表。只有真正不走原版
`ItemCooldownManager` 的职业自定义冷却才应在 appender 中读取自己的同步组件。

## 公共辅助方法

- `getRemainingCooldownTicks(player, item)`：读取当前实际剩余 tick；无活动条目时返回 0。
- `formatCooldownTicks(ticks)`：格式化为 `6s`、`1m5s`；最后不足一秒时显示 `1s`。
- `COOLDOWN_COLOR`、`REGULAR_TOOLTIP_COLOR`、`LETTER_COLOR`：Wathe 标准 tooltip 颜色。

该 API 标记为客户端环境。注册和动态 appender 必须放在扩展的客户端初始化代码中，服务端攻击、
购买与能力判定仍应读取服务端真实状态，不能依据 tooltip 文本判断。
