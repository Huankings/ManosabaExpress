# Wathe 客户端输入公开 API

`dev.doctor4t.wathe.api.client.input.ClientInputApi` 统一调度客户端 KeyBinding、原始键盘事件、
鼠标按钮、鼠标滚轮以及 `MinecraftClient` 最终游戏动作。扩展职业不应再直接 mixin
`KeyBinding`、`Keyboard`、`Mouse`、`doAttack`、`doItemUse`、`doItemPick` 或
`handleBlockBreaking`。

## 规则优先级和返回值

- priority 越大越先执行；相同 priority 后注册的规则先执行。
- 相同 `Identifier` 再次注册会替换旧规则。
- `PASS`：继续询问低优先级规则。
- `ALLOW`：停止规则链并保留原版输入/动作。
- `BLOCK`：停止规则链并取消输入/动作。
- KeyBinding 的 `KeyResult.override(boolean)`：直接覆盖本次读取结果。
- 最终动作的 `ActionResult.handled(boolean)`：扩展已自行完成动作并取消原版执行；布尔值是
  `doAttack` 的返回值。

Wathe 自带的交换手、跳跃、切视角、丢弃和进度键限制使用 priority 0。扩展若只想为特定职业
放开 Wathe 默认限制，应使用更高优先级并返回 `ALLOW` 或 `override(...)`，不要修改全局世界配置。

## 注册入口

```java
ClientInputApi.registerKeyBindingRule(id, priority, context -> ClientInputApi.KeyResult.pass());
ClientInputApi.registerKeyboardRule(id, priority, context -> ClientInputApi.Decision.PASS);
ClientInputApi.registerMouseButtonRule(id, priority, context -> ClientInputApi.Decision.PASS);
ClientInputApi.registerMouseScrollRule(id, priority, context -> ClientInputApi.Decision.PASS);
ClientInputApi.registerActionRule(id, priority, context -> ClientInputApi.ActionResult.pass());
ClientInputApi.registerInputTickHandler(id, priority, context -> { });
```

最终动作类型包括 `ATTACK`、`ITEM_USE`、`ITEM_PICK` 和 `BLOCK_BREAKING`。`InputTickHandler`
在每次 `handleInputEvents` 开始时运行，适合清理状态，不应用来替代最终动作规则。

## 按键队列清理

Minecraft 的 KeyBinding 同时保存当前 `pressed` 和尚未消费的 `timesPressed`。只把读取结果改成
false 会让短按在状态结束后补执行，因此 API 提供：

- `clearPressQueue(key)`：只清 `timesPressed`，保留持续按住状态。
- `releaseKey(key)`：同时清 `pressed` 和 `timesPressed`。
- `isPhysicallyPressed(client, key)`：按玩家真实键位配置读取键盘、扫描码或鼠标物理状态。

持续移动、跳跃等输入通常只清短按队列，以便状态解除后仍按着键的玩家自然恢复；攻击、使用、
背包、丢弃和选槽等离散动作通常使用 `releaseKey`。

## 客户端与服务端边界

这些规则只负责本地输入体验和竞态兜底。攻击、物品使用、职业能力和自定义网络包必须继续在服务端
验证玩家状态、职业、距离、冷却和目标权限。客户端 BLOCK 不能作为安全边界。

## 扩展代码组织

扩展应建立一个客户端输入聚合类，例如 `NoellesClientInputBootstrap`，它只调用各职业或词条的
`*InputRules.register()`。状态判断、按键集合和清理策略必须放在对应职业目录，不能重新堆进一个
认识所有组件的大型类。

## 回合初始化前事件

世界级组件如果必须在新局职业/词条分配前清理，使用
`GameEvents.ON_BEGIN_INITIALIZE`。玩家组件的 Harpy 扩展清理应继续使用 Harpy
`ResetPlayerEvent`；不要为同一语义重新注入 `GameFunctions.resetPlayer`。
