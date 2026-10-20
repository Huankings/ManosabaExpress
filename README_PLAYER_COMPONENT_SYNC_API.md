# 玩家组件选择性观察者同步

`dev.doctor4t.wathe.api.sync.PlayerComponentSyncApi` 解决“组件默认本人同步，但少数职业需要在客户端观察目标”的情况。

例如 NoellesRoles 的制毒师、医师和酒保本能，直接在客户端读取目标 `PlayerPoisonComponent.poisonTicks`。
仅允许中毒者本人收包会使这些观察者看不到真毒；服务端可以读取目标组件，并不代表观察者客户端也有该组件的最新值。

## 注册入口

在扩展通用初始化中注册，不要只在客户端入口注册：

```java
PlayerComponentSyncApi.registerObserverRule(
        Identifier.of("my_mod", "sync/poison_viewers"),
        PlayerPoisonComponent.KEY,
        PlayerComponentSyncApi.DEFAULT_PRIORITY,
        context -> {
            GameWorldComponent game = GameWorldComponent.KEY.get(context.recipient().getWorld());
            return game.isRunning()
                    && GameFunctions.isPlayerAliveAndSurvival(context.recipient())
                    && game.isRole(context.recipient(), MY_ROLE);
        }
);
```

- `owner()` 是状态所属玩家，`recipient()` 是需要观察该目标的客户端玩家。
- 所属玩家本人始终允许；API 不自行授予其他组件的观察权限，Wathe 仅显式登记下文的原生本能心情规则，扩展再按实际需要注册自己的规则。
- 规则返回 `true` 表示允许，`false` 表示继续后面的规则。priority 越高越先询问，同 id 重复注册替换旧规则。
- 规则在服务端执行。使用服务端职业、词条、组件等真实状态，不能读取客户端本能按键或 `MinecraftClient`。
- 现有 CCA 同步只面向正在追踪目标的客户端；API 不扩大实体追踪距离，也不把全服坐标或玩家状态广播给观察者。
- 授权的是整个组件，包含其默认 NBT 中的字段。若只需一枚布尔标记且不希望给观察者完整组件，应另做专门的最小显示状态，不能误把本 API 当字段级过滤。

## 转职、死亡与回溯

目标开始中毒、加速毒发、解毒或死亡重置时，仍通过原有 `sync()` 立即发送，客户端倒计时本地推进。
这不会新增逐 tick 毒药同步。

对于局中转职，观察者可能刚获得透视能力，而目标没有重新中毒。可在最终职业已写入后调用：

```java
PlayerComponentSyncApi.syncTrackedToRecipient(PlayerPoisonComponent.KEY, serverPlayer);
```

它会立即重新检查当前追踪目标；新增允许时补发真实状态，失去已有权限时发送空状态清除客户端缓存，不会修改服务端目标数据。
从未获权的普通职业不会因此收到一轮无意义空包，观察者自己的组件也仍交给原 CCA 生命周期同步。
NoellesRoles 在职业分配处理结束后调用此入口。

除此之外，API 每 `GameConstants.COMPONENT_OBSERVER_REFRESH_INTERVAL_TICKS`（默认 20 tick）检查一次授权关系。
权限新增时补发，失去权限时发送一次空 NBT 清理，授权未变化时不发包。因此不触发职业分配事件的 soft 转职、回溯、死亡或临时观察能力也能在下一次检查中正确更新。

网络观察缓存不属于玩法数据，不写存档、不参加时停快照。断线时删除对应实体引用，服务器停止时清空；重连或重生的新实体会重新检查授权。
客户端 provider 仍须判断当前职业和存活，避免在权限校验间隔内显示不应继续显示的信息。

## 其他组件的扩展口

当前已接入 `poison`、`mood`、`stamina`、`shop`、`note`、`grenade_throw_mode`、`instinct` 七个 Wathe 玩家组件，分别传对应的 `KEY` 即可。
Wathe 的 `game/instinct/InstinctMoodSyncHandler` 注册原生本能所需的心情观察规则：对局内无辜存活目标，允许具有杀手能力的存活观察者以及旁观/创造视角接收心情组件，兼容扩展杀手职业。杀手之间的固定红色分支不需要目标心情。不能把心情改回仅本人同步，否则目标默认满值会导致本能一直绿色。

除该内置心情规则及 NoellesRoles 注册的毒药规则，其他组件仍然只有本人同步；体力和心情的 5 tick 校准节奏保持不变。心情仍按原有完整 NBT 协议发送，任务结构变化及主动设置立即同步，连续下降低频校准；本轮没有新增逐 tick 广播，也没有改变颜色阈值的严格小于边界。

上述组件使用默认 CCA NBT 同步，空 NBT 可还原客户端默认值。API 会拒绝未支持的组件，避免向自定义二进制 codec 误发空 NBT。
将来若改动支持组件的同步格式或默认读取行为，需要同时调整撤销授权时的清空协议。
`PlayerBlackoutEffectComponent` 仍保留此前注释掉的发送代码与禁止同步设置，不在本 API 范围内。

更新时先编译 Wathe、复制新 jar 到扩展 `libs`，再编译扩展。服务端和客户端使用配套的新 Wathe/NoellesRoles jar。
