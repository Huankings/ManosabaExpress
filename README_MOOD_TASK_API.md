# Wathe 心情任务 API

这份文档给扩展 Mod 接入心情任务使用。当前心情任务已经从旧 `PlayerMoodComponent.Task` enum 迁到注册式 API：

- 新任务用 `MoodTaskApi.registerTask(...)` 注册；
- 任务点透视类型用 `MoodTaskPointApi.registerTaskPoint(...)` 注册；
- 地图扫描追加任务点用 `MoodTaskPointApi.registerScanHandler(...)`；
- 指定发放任务用 `MoodTaskApi.assignTask(player, taskId)`；
- 阻止 Wathe 自动发放、外部随机发放或指定发放任务用 `MoodTaskApi.registerAssignmentRule(...)`；
- 删除任务和完成任务是两个不同语义。

## 任务定义

扩展任务默认不会进入普通随机池，只能被指定发放。只有明确希望普通玩家随机刷到这个任务时，才调用 `.randomlyAssignable()`。

```java
public static final Identifier MY_TASK = MyMod.id("my_task");
public static final Identifier MY_TASK_POINT = MyMod.id("my_task_point");

public static void init() {
    MoodTaskPointApi.registerTaskPoint(
            MY_TASK_POINT,
            "hud.task_point.mymod.my_task_point",
            0x66CCFF
    );

    MoodTaskApi.registerTask(MoodTaskDefinition.builder(
                    MY_TASK,
                    "task.mymod.my_task",
                    player -> new MyTask(),
                    (player, nbt) -> new MyTask(nbt.getInt("timer"))
            )
            .taskPoints(MY_TASK_POINT)
            .build());
}
```

`TrainTask#isFulfilled(player)` 返回 true 后，Wathe 会自动走完成流程：移除任务、回复心情、发送 HUD 动画、写回放并触发 `TaskCompletionApi`。

## 任务点扫描

如果任务点来自地图方块，在服务端注册扫描 handler：

```java
MoodTaskPointApi.registerScanHandler(
        MyMod.id("my_task_point_scan"),
        MoodTaskPointApi.DEFAULT_PRIORITY,
        context -> {
            if (context.state().isOf(MyBlocks.MY_BLOCK)) {
                context.addTaskPoint(MY_TASK_POINT);
            }
        }
);
```

Wathe 已经负责限制扫描范围：只扫描当前列车复制区域，并且只保留 `playArea` 内的坐标。扩展 handler 不应该再全图扫描。

## 指定发放、删除和完成

```java
MoodTaskApi.assignTask(target, MY_TASK);
MoodTaskApi.removeTask(target, MY_TASK);
MoodTaskApi.completeTask(target, MY_TASK, true);
```

- `assignTask`：指定发放任务，不要求任务进入随机池。
- `removeTask`：只删除任务，不加心情、不触发完成事件、不写任务完成回放。
- `completeTask`：按正常完成流程结算；`rewardMood=true` 时会按 Wathe 心情规则回复心情。

## 发放拦截

如果扩展职业要完全接管任务节奏，不要在 tick 里等任务出现后再删除。那会导致客户端看到任务短暂闪现。应在 Wathe 发放前注册拦截规则：

```java
MoodTaskApi.registerAssignmentRule(
        MyMod.id("my_role_task_assignment"),
        MoodTaskApi.DEFAULT_PRIORITY + 100,
        context -> shouldBlockTaskAssignment(context.player(), context.source(), context.taskId())
                ? MoodTaskApi.AssignmentDecision.DENY
                : MoodTaskApi.AssignmentDecision.PASS
);
```

`context.source()` 用于区分发放来源：

| 来源 | 含义 |
| --- | --- |
| `INTERNAL_PRIMARY_COOLDOWN` | Wathe 在任务栏为空且第一个任务冷却结束后自动刷任务 |
| `INTERNAL_SLOT_REFILL` | Wathe 因低心情并行槽位或任务完成后阈值检查自动补任务 |
| `EXTERNAL_RANDOM` | 扩展或调试入口调用 `assignRandomTask` / `assignRandomTasks` |
| `EXTERNAL_SPECIFIC` | 扩展或调试入口调用 `assignTask(player, taskId)` |

随机发放会在每个候选任务进入任务栏前询问规则。某个 task id 被拒绝时，Wathe 会继续尝试其它候选；如果全部候选都被拒绝，随机发放结果会表现为没有可用任务。指定发放被拒绝时，`TaskAssignmentResult.status()` 会返回 `ASSIGNMENT_DENIED`。

管理员调试指令：

```text
/wathe:moodTask list
/wathe:moodTask assign <task> [player]
/wathe:moodTask remove <task> [player]
/wathe:moodTask complete <task> [player]
```

不写 `[player]` 时默认作用于执行指令的玩家自己。

## 完成拦截和收入规则

阻止任务确认完成，用：

```java
MoodTaskApi.registerCompletionRule(
        MyMod.id("block_task_completion"),
        MoodTaskApi.DEFAULT_PRIORITY + 100,
        context -> shouldBlock(context.player())
                ? MoodTaskApi.CompletionDecision.DENY
                : MoodTaskApi.CompletionDecision.PASS
);
```

任务完成但跳过 Wathe 默认收入，用：

```java
TaskCompletionApi.registerTaskIncomeRule(
        MyMod.id("suppress_income"),
        TaskCompletionApi.DEFAULT_PRIORITY + 100,
        context -> shouldSuppress(context.player(), context.taskId())
                ? TaskCompletionApi.TaskIncomeDecision.SUPPRESS_DEFAULT_INCOME
                : TaskCompletionApi.TaskIncomeDecision.PASS
);
```

这个规则不会取消 `TaskCompletionApi.AFTER_TASK_COMPLETE`，只跳过 Wathe 默认金币 / 任务币收入。
