# Wathe 扩展商店多货币机制教程

本文档说明 Wathe 当前的扩展商店货币机制，包括：

- 如何注册一个新的货币 / 材料；
- 如何给玩家增加、设置、读取某种货币；
- 商店价格 `ShopPrice` 的表达格式；
- Wathe 默认商店是如何写多货币价格的；
- 扩展职业应该如何注册商店、交付商品和让 Wathe 统一扣款；
- 扩展职业如何读取 Wathe 默认商店的金币价、任务币价、某一组支付方案里的某一种货币；
- 当价格需要加、减、乘、除时，应该如何显式处理，避免误把任务币价格带进中立职业商店。

> 当前状态：任务币 API、贴图、指令和 `ShopPrice` 多货币格式仍保留，方便后续继续实验；但 Wathe 默认杀手商店当前已恢复为纯金币价格。杀手完成任务和击杀获得任务币的常量也暂时设为 `0`，任务币 HUD 默认注册逻辑暂停。本文中多货币商店示例仍可作为后续重新启用任务币或扩展模组自定义货币时的参考格式。

下面的代码示例以 Wathe/Yarn 命名为主，例如 `Identifier`、`ItemStack#getDefaultStack()`。  
如果你的扩展工程使用 Mojmap 命名，常见对应关系是：

- `Identifier` -> `ResourceLocation`
- `ItemStack#getDefaultStack()` -> `ItemStack#getDefaultInstance()`
- `PlayerEntity` -> `Player`

## 1. 核心概念

### 1.1 货币 id

每种货币都用一个 `Identifier` 表示。

Wathe 内置两种货币：

```java
EconomyApi.MONEY      // wathe:money，原金币
EconomyApi.TASK_MONEY // wathe:task_money，任务币
```

扩展模组如果要注册自己的货币，也应该使用自己的命名空间：

```java
public static final Identifier BLOOD_TOKEN = MyMod.id("blood_token");
```

不要复用 `wathe:money` 或 `wathe:task_money` 表示自己的新材料。  
同一种货币 id 会共享同一份玩家余额。

### 1.2 货币数量

商店价格里的某一段货币数量由 `CurrencyAmount` 表示：

```java
CurrencyAmount.money(50)                     // 50 金币
CurrencyAmount.taskMoney(25)                 // 25 任务币
CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 3)  // 3 个自定义血契币
```

`CurrencyAmount` 只描述“哪种货币 + 数量”。  
它不决定玩家有没有钱，也不负责扣款。

### 1.3 商店价格

商店价格由 `ShopPrice` 表示。

它的结构是：

```text
ShopPrice
  option 0: CurrencyAmount + CurrencyAmount + ...
  OR
  option 1: CurrencyAmount + CurrencyAmount + ...
  OR
  option 2: ...
```

也就是说：

- 多个 `option` 之间是“或”；
- 同一个 `option` 里的多个 `CurrencyAmount` 是“和”；
- 一个商品购买成功时，只会选择并扣除其中一个 `option`。

例子：

```java
// 50 金币
ShopPrice.money(50)

// 50 金币 + 25 任务币
ShopPrice.allOf(
        CurrencyAmount.money(50),
        CurrencyAmount.taskMoney(25)
)

// 100 金币 或 50 任务币
ShopPrice.anyOf(
        ShopPrice.option(CurrencyAmount.money(100)),
        ShopPrice.option(CurrencyAmount.taskMoney(50))
)

// 350 金币 + 25 任务币，或者 300 金币 + 75 任务币
ShopPrice.anyOf(
        ShopPrice.option(
                CurrencyAmount.money(350),
                CurrencyAmount.taskMoney(25)
        ),
        ShopPrice.option(
                CurrencyAmount.money(300),
                CurrencyAmount.taskMoney(75)
        )
)
```

### 1.4 支付方案选择规则

玩家同时买得起多个 option 时，Wathe 会用 `ShopPrice#selectPayment(...)` 选择实际扣款方案。

当前规则是：

1. 先选“所有货币数量相加最少”的可支付方案；
2. 如果总量相同，再选定义顺序更靠前的方案。

例如：

```java
ShopPrice.anyOf(
        ShopPrice.option(CurrencyAmount.money(100)),
        ShopPrice.option(CurrencyAmount.taskMoney(50))
)
```

如果玩家同时有 100 金币和 50 任务币，实际会优先扣 50 任务币，因为 `50 < 100`。

再如：

```java
ShopPrice.anyOf(
        ShopPrice.option(CurrencyAmount.money(350), CurrencyAmount.taskMoney(25)), // total 375
        ShopPrice.option(CurrencyAmount.money(300), CurrencyAmount.taskMoney(75))  // total 375
)
```

两个方案总量都是 375 时，会选择 option 0，也就是 `350 金币 + 25 任务币`。

如果你希望某个商品强制优先金币或强制优先任务币，需要把价格设计成只有一个 option，或者调整数值让期望方案的总量更小。

## 2. 注册自定义货币

### 2.1 最小注册示例

在你的扩展模组初始化时调用：

```java
public final class MyEconomy {
    public static final Identifier BLOOD_TOKEN = MyMod.id("blood_token");
    public static final String BLOOD_TOKEN_ICON = "\uE783";

    public static void init() {
        EconomyApi.registerCurrency(
                BLOOD_TOKEN,
                BLOOD_TOKEN_ICON,
                "currency.mymod.blood_token",
                context -> context.role() == MyRoles.BLOOD_MAGE
        );
    }
}
```

参数含义：

```java
EconomyApi.registerCurrency(
        Identifier id,                 // 货币 id
        String icon,                   // HUD 和价格里显示的图标字符
        String translationKey,          // 不使用图标时的翻译名
        CurrencyHudPredicate predicate  // 该货币是否允许显示在右上角 HUD
)
```

### 2.2 HUD 显示规则

注册货币时传入的 `CurrencyHudPredicate` 决定“这个玩家是否有资格看见这种货币”。

但真正渲染 HUD 时还有一个额外规则：

- 余额 `<= 0` 时不显示；
- 余额从正数消费到 `0` 时，会先按滚动数字动画过渡到 `0`，然后淡出消失。

常见显示规则：

```java
// 只给某个职业显示
context -> context.role() == MyRoles.BLOOD_MAGE

// 只给拥有杀手能力的玩家显示
context -> context.gameWorld().canUseKillerFeatures(context.player())

// 给多个职业显示
context -> context.role() == MyRoles.BLOOD_MAGE || context.role() == MyRoles.CULTIST
```

Wathe 内置任务币原本可以这样注册：

```java
EconomyApi.registerCurrency(
        EconomyApi.TASK_MONEY,
        EconomyApi.TASK_MONEY_ICON,
        "currency.wathe.task_money",
        context -> context.gameWorld().canUseKillerFeatures(context.player())
);
```

但当前任务币商店实验已暂停，Wathe 本体暂时没有把任务币注册进 HUD。  
所以任务币目前主要保留为 API / 指令 / 后续实验入口；如果重新启用，上面的注册格式可以直接作为参考。  
任务币默认只适合杀手阵营或拥有杀手能力的职业。  
中立职业如果无法获得任务币，不应该直接复制带任务币条件的默认商店价格。

### 2.3 图标字体资源

Wathe 的金币和任务币图标使用私有码位字符：

```java
EconomyApi.MONEY_ICON      // "\uE781"
EconomyApi.TASK_MONEY_ICON // "\uE782"
```

资源在：

```text
src/main/resources/assets/wathe/textures/font/coin.png
src/main/resources/assets/wathe/textures/font/taskcoin.png
src/main/resources/assets/minecraft/font/default.json
```

自定义货币也可以采用同样方式：

1. 准备图标贴图，例如：

```text
assets/mymod/textures/font/blood_token.png
```

2. 在 `assets/minecraft/font/default.json` 添加 provider，选择一个不冲突的私有码位：

```json
{
  "type": "bitmap",
  "file": "mymod:font/blood_token.png",
  "ascent": 7,
  "height": 8,
  "chars": [
    "\uE783"
  ]
}
```

3. 注册货币时使用同一个字符：

```java
public static final String BLOOD_TOKEN_ICON = "\uE783";
```

注意：

- 私有码位不要和 Wathe 或其他扩展重复；
- 图标字符必须能被客户端字体加载，否则 HUD/商店价格会显示成缺字；
- 如果你暂时不想做图标，可以传空字符串 `""`，此时 `EconomyApi.formatCurrencyAmount(..., false)` 会使用翻译名，但商店价格图标显示会不好看，建议最终仍然提供图标。

### 2.4 语言文件

注册货币时的 translation key 需要写入语言文件：

```json
{
  "currency.mymod.blood_token": "血契币"
}
```

英文：

```json
{
  "currency.mymod.blood_token": "Blood Token"
}
```

## 3. 玩家货币余额读写

货币余额统一存放在 `PlayerShopComponent`。

获取组件：

```java
PlayerShopComponent shop = PlayerShopComponent.KEY.get(player);
```

### 3.1 金币兼容接口

旧金币接口仍然保留：

```java
shop.balance              // public int，旧扩展兼容字段
shop.addToBalance(50);    // 增加 50 金币
shop.setBalance(100);     // 设置金币为 100
```

这些都只代表 `EconomyApi.MONEY`。

### 3.2 通用货币接口

新增货币必须使用通用接口：

```java
int money = shop.getCurrencyAmount(EconomyApi.MONEY);
int taskMoney = shop.getCurrencyAmount(EconomyApi.TASK_MONEY);
int blood = shop.getCurrencyAmount(MyEconomy.BLOOD_TOKEN);

shop.addCurrencyAmount(EconomyApi.TASK_MONEY, 25);
shop.addCurrencyAmount(MyEconomy.BLOOD_TOKEN, 1);

shop.setCurrencyAmount(MyEconomy.BLOOD_TOKEN, 0);
```

`setCurrencyAmount` 会自动把负数夹到 0。  
非金币货币为 0 时会从内部 map 中移除。

### 3.3 发放自定义货币

击杀奖励：

```java
if (gameWorld.canUseKillerFeatures(killer)) {
    PlayerShopComponent.KEY.get(killer).addCurrencyAmount(MyEconomy.BLOOD_TOKEN, 1);
}
```

任务完成奖励：

```java
TaskCompletionApi.AFTER_TASK_COMPLETE.register(context -> {
    if (context.role() == MyRoles.BLOOD_MAGE) {
        PlayerShopComponent.KEY.get(context.player()).addCurrencyAmount(MyEconomy.BLOOD_TOKEN, 2);
    }
});
```

关于 `TaskCompletionApi.registerTaskIncomeProvider(...)`：

- 它是旧的“任务金币收入”兼容入口；
- 非杀手职业仍可用它发金币；
- 任务币实验启用时，拥有杀手能力的玩家完成任务可以改为发任务币，并且不叠加旧任务金币 provider；
- 当前 Wathe 的任务币任务奖励常量为 `0`，所以默认不会发任务币；
- 如果杀手职业有特殊词条需要额外发金币，请使用 `AFTER_TASK_COMPLETE` 单独处理。

## 4. 商店价格格式

### 4.1 纯金币价格

旧写法仍然可用：

```java
new ShopEntry(WatheItems.REVOLVER.getDefaultStack(), 250, ShopEntry.Type.WEAPON)
```

等价于：

```java
new ShopEntry(
        WatheItems.REVOLVER.getDefaultStack(),
        ShopPrice.money(250),
        ShopEntry.Type.WEAPON
)
```

### 4.2 单方案，多货币 AND

开锁器示例：需要 50 金币和 25 任务币同时满足。

```java
new ShopEntry(
        WatheItems.LOCKPICK.getDefaultStack(),
        ShopPrice.allOf(
                CurrencyAmount.money(50),
                CurrencyAmount.taskMoney(25)
        ),
        ShopEntry.Type.TOOL
)
```

显示效果是两行：

```text
50 金币图标
25 任务币图标
```

购买条件是：

```text
金币 >= 50 AND 任务币 >= 25
```

### 4.3 多方案 OR

匕首示例：100 金币或者 50 任务币。

```java
new ShopEntry(
        WatheItems.KNIFE.getDefaultStack(),
        ShopPrice.anyOf(
                ShopPrice.option(CurrencyAmount.money(100)),
                ShopPrice.option(CurrencyAmount.taskMoney(50))
        ),
        ShopEntry.Type.WEAPON
)
```

显示效果是三行：

```text
100 金币图标
或
50 任务币图标
```

购买条件是：

```text
金币 >= 100 OR 任务币 >= 50
```

### 4.4 多方案，每组都是多货币

疯魔模式示例：

```java
new ShopEntry(
        WatheItems.PSYCHO_MODE.getDefaultStack(),
        ShopPrice.anyOf(
                ShopPrice.option(
                        CurrencyAmount.money(350),
                        CurrencyAmount.taskMoney(25)
                ),
                ShopPrice.option(
                        CurrencyAmount.money(300),
                        CurrencyAmount.taskMoney(75)
                )
        ),
        ShopEntry.Type.WEAPON
) {
    @Override
    public boolean onBuy(@NotNull PlayerEntity player) {
        return PlayerShopComponent.usePsychoMode(player);
    }
}
```

显示效果是五行：

```text
350 金币图标
25 任务币图标
或
300 金币图标
75 任务币图标
```

购买条件是：

```text
(金币 >= 350 AND 任务币 >= 25)
OR
(金币 >= 300 AND 任务币 >= 75)
```

option 索引：

```text
option 0: 350 金币 + 25 任务币
option 1: 300 金币 + 75 任务币
```

后面读取默认价格时会用到这个索引。

### 4.5 自定义货币价格

假设你注册了：

```java
public static final Identifier BLOOD_TOKEN = MyMod.id("blood_token");
```

纯自定义货币：

```java
new ShopEntry(
        MyItems.BLOOD_DAGGER.getDefaultStack(),
        ShopPrice.allOf(CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 3)),
        ShopEntry.Type.WEAPON
)
```

金币 + 自定义货币：

```java
new ShopEntry(
        MyItems.BLOOD_DAGGER.getDefaultStack(),
        ShopPrice.allOf(
                CurrencyAmount.money(150),
                CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 2)
        ),
        ShopEntry.Type.WEAPON
)
```

金币或自定义货币：

```java
new ShopEntry(
        MyItems.BLOOD_DAGGER.getDefaultStack(),
        ShopPrice.anyOf(
                ShopPrice.option(CurrencyAmount.money(200)),
                ShopPrice.option(CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 4))
        ),
        ShopEntry.Type.WEAPON
)
```

## 5. Wathe 当前默认商店价格

默认商店定义在：

```text
src/main/java/dev/doctor4t/wathe/game/GameConstants.java
```

当前实际启用的默认商店价格已经恢复为纯金币：

```java
new ShopEntry(WatheItems.KNIFE.getDefaultStack(), 100, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.PSYCHO_MODE.getDefaultStack(), 350, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.LOCKPICK.getDefaultStack(), 50, ShopEntry.Type.TOOL);
```

这些商品之前的任务币交易方案保留在 `GameConstants` 注释中，方便后续重新启用。下面记录的是“实验性任务币方案”的参考格式，不是当前实际启用价格。

### 5.1 匕首 KNIFE

```java
ShopPrice.anyOf(
        ShopPrice.option(CurrencyAmount.money(SHOP_KNIFE_MONEY_PRICE)),
        ShopPrice.option(CurrencyAmount.taskMoney(SHOP_KNIFE_TASK_MONEY_PRICE))
)
```

实验方案数值：

```text
option 0: 100 金币
option 1: 50 任务币
```

### 5.2 开锁器 LOCKPICK

```java
ShopPrice.allOf(
        CurrencyAmount.money(SHOP_LOCKPICK_MONEY_PRICE),
        CurrencyAmount.taskMoney(SHOP_LOCKPICK_TASK_MONEY_PRICE)
)
```

实验方案数值：

```text
option 0: 50 金币 + 25 任务币
```

### 5.3 疯魔模式 PSYCHO_MODE

```java
ShopPrice.anyOf(
        ShopPrice.option(
                CurrencyAmount.money(SHOP_PSYCHO_MODE_MONEY_PRICE_PRIMARY),
                CurrencyAmount.taskMoney(SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_PRIMARY)
        ),
        ShopPrice.option(
                CurrencyAmount.money(SHOP_PSYCHO_MODE_MONEY_PRICE_SECONDARY),
                CurrencyAmount.taskMoney(SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_SECONDARY)
        )
)
```

实验方案数值：

```text
option 0: 350 金币 + 25 任务币
option 1: 300 金币 + 75 任务币
```

### 5.4 纯金币商品

其它默认商品也仍是纯金币旧格式：

```java
new ShopEntry(WatheItems.REVOLVER.getDefaultStack(), 250, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.GRENADE.getDefaultStack(), 300, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.KNIFE.getDefaultStack(), 100, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.PSYCHO_MODE.getDefaultStack(), 350, ShopEntry.Type.WEAPON);
new ShopEntry(WatheItems.POISON_VIAL.getDefaultStack(), 70, ShopEntry.Type.POISON);
new ShopEntry(WatheItems.SCORPION.getDefaultStack(), 40, ShopEntry.Type.POISON);
new ShopEntry(WatheItems.FIRECRACKER.getDefaultStack(), 10, ShopEntry.Type.TOOL);
new ShopEntry(WatheItems.LOCKPICK.getDefaultStack(), 50, ShopEntry.Type.TOOL);
new ShopEntry(WatheItems.CROWBAR.getDefaultStack(), 25, ShopEntry.Type.TOOL);
new ShopEntry(WatheItems.BODY_BAG.getDefaultStack(), 70, ShopEntry.Type.TOOL);
new ShopEntry(WatheItems.BLACKOUT.getDefaultStack(), 250, ShopEntry.Type.TOOL);
new ShopEntry(new ItemStack(WatheItems.NOTE, 4), 10, ShopEntry.Type.TOOL);
```

这些 `int` 构造器都会转成：

```java
ShopPrice.money(price)
```

## 6. 注册扩展职业商店

### 6.1 完全替换某个职业的商店

如果某个职业需要完整独立的一张商店表，使用：

```java
ShopApi.registerRoleShop(MyRoles.BLOOD_MAGE, new RoleShopProvider() {
    @Override
    public @NotNull List<ShopEntry> getShopEntries(@NotNull PlayerEntity player) {
        return List.of(
                ShopEntry.giveToInventory(
                        MyItems.BLOOD_DAGGER.getDefaultStack(),
                        ShopPrice.allOf(
                                CurrencyAmount.money(150),
                                CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 2)
                        ),
                        ShopEntry.Type.WEAPON
                )
        );
    }

    @Override
    public @NotNull ShopPurchaseResult purchase(@NotNull ShopPurchaseContext context) {
        return ShopApi.defaultPurchase(context);
    }
});
```

如果只是静态列表，也可以：

```java
ShopApi.registerStaticRoleShop(MyRoles.BLOOD_MAGE, () -> MY_SHOP_ENTRIES);
```

注意：`registerRoleShop` 后，该职业不会再读取 Wathe 默认杀手商店，而是读取 provider 返回的列表。

### 6.2 修改默认杀手商店

如果只是给某个杀手职业加一两个商品、移除某个默认商品、替换某个格子，优先用 `registerShopModifier`：

```java
ShopApi.registerShopModifier(
        MyMod.id("blood_mage_shop"),
        ShopApi.DEFAULT_PRIORITY,
        MyBloodMageShopHandler::modifyShop
);
```

示例：

```java
public static void modifyShop(@NotNull ShopContext context, @NotNull List<ShopEntry> entries) {
    if (context.role() != MyRoles.BLOOD_MAGE) {
        return;
    }

    entries.add(new ShopEntry(
            MyItems.BLOOD_DAGGER.getDefaultStack(),
            ShopPrice.allOf(
                    CurrencyAmount.money(150),
                    CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 2)
            ),
            ShopEntry.Type.WEAPON
    ));
}
```

修改器的特点：

- 默认杀手商店会先生成；
- 然后你的 modifier 在这张表上增删改；
- 没有被你替换的默认条目会保留原本的 `ShopPrice`；当前默认条目是纯金币，如果后续重新启用任务币方案，也会自然保留任务币价格；
- 如果你用 `new ShopEntry(stack, int, type)` 替换某个默认条目，被替换的条目会变成纯金币价格；
- 如果你明确想完整继承原条目的多货币价格，需要显式读取并传入 `ShopPrice`。

### 6.3 非杀手职业商店的商品交付

`ShopEntry#onBuy` 的默认实现只允许拥有杀手能力的玩家把商品放进快捷栏。

非杀手职业、平民职业、中立职业如果也有商店，应使用以下任意方式：

```java
// 直接放入快捷栏空位
ShopEntry.directToHotbar(stack, price, type)
ShopEntry.directToHotbar(stack, shopPrice, type)

// 交给玩家背包自动接收
ShopEntry.giveToInventory(stack, price, type)
ShopEntry.giveToInventory(stack, shopPrice, type)

// 购买即执行动作
ShopEntry.action(stack, price, type, player -> doSomething(player))
ShopEntry.action(stack, shopPrice, type, player -> doSomething(player))
```

如果你自己实现 provider 的 `purchase(...)`，也可以自己交付商品，但不要自己扣钱。

## 7. 购买结算流程

Wathe 的统一购买流程在 `PlayerShopComponent#tryBuy(...)`。

简化流程：

```text
1. 根据玩家职业解析商店列表。
2. 读取点击的 ShopEntry。
3. 用 entry.shopPrice().selectPayment(shop) 选择玩家当前买得起的支付方案。
4. 调用 provider.purchase(context)，让扩展只负责判断商品是否交付成功。
5. 如果购买成功，Wathe 统一扣除刚才选中的 ShopPayment。
6. Wathe 统一播放音效、同步余额、记录回放。
```

### 7.1 provider.purchase 应该做什么

应该做：

```java
public static @NotNull ShopPurchaseResult purchase(@NotNull ShopPurchaseContext context) {
    PlayerEntity player = context.player();
    ShopEntry entry = context.entry();
    Item item = entry.stack().getItem();

    if (!context.canAffordEntry() || player.getItemCooldownManager().isCoolingDown(item)) {
        return ShopPurchaseResult.FAIL_SHOW_MESSAGE;
    }

    return deliverPurchasedStack(player, entry.stack())
            ? ShopPurchaseResult.SUCCESS
            : ShopPurchaseResult.FAIL_SHOW_MESSAGE;
}
```

不应该做：

```java
// 不要在 provider.purchase 里扣钱
shop.setCurrencyAmount(...);
shop.addCurrencyAmount(..., -price);

// 不要在 provider.purchase 里播放购买成功音效
ShopApi.playBuySound(player);

// 不要在 provider.purchase 里主动 sync
shop.sync();
```

这些公共副作用由 Wathe 统一处理。

### 7.2 ShopPurchaseContext 里能读什么

```java
context.player();          // 玩家
context.shop();            // PlayerShopComponent
context.entry();           // 当前 ShopEntry
context.index();           // 当前商品索引
context.gameWorld();       // GameWorldComponent
context.role();            // 当前职业，可能为 null
context.roleSpecificShop();// 是否来自某个职业专属 provider
```

余额读取：

```java
context.balance();                       // 旧兼容：只读金币
context.currencyBalance(EconomyApi.MONEY);
context.currencyBalance(EconomyApi.TASK_MONEY);
context.currencyBalance(MyEconomy.BLOOD_TOKEN);
```

价格判断：

```java
context.canAffordEntry();
```

`canAffordEntry()` 会根据当前 `entry.shopPrice()` 判断全部货币方案，不只是金币。

### 7.3 旧接口 entry.price()

`ShopEntry#price()` 仍然存在，但它是旧兼容接口。

规则：

- 纯金币商品：返回金币价格；
- 多货币商品：优先返回第 0 组支付方案里的金币数量；
- 如果第 0 组没有金币，则返回第 0 组所有货币数量总和。

所以：

```java
KNIFE       // option 0 = 100 金币，entry.price() 返回 100
LOCKPICK    // 当前 option 0 = 50 金币，entry.price() 返回 50
PSYCHO_MODE // 当前 option 0 = 350 金币，entry.price() 返回 350
```

如果后续重新启用任务币实验方案，开锁器的 option 0 可能再次变成 `50 金币 + 25 任务币`，疯魔模式 option 0 可能再次变成 `350 金币 + 25 任务币`；此时 `entry.price()` 仍只会返回第 0 组金币数。

不要用 `entry.price()` 判断多货币商品能否购买。  
新代码应该用：

```java
context.canAffordEntry()
entry.shopPrice().canAfford(shop)
```

## 8. 读取 Wathe 默认商店价格

### 8.1 读取完整价格

```java
ShopPrice price = ShopApi.getDefaultShopPrice(WatheItems.LOCKPICK);
if (price == null) {
    price = ShopPrice.money(50);
}
```

这个会返回整套 `ShopPrice`。  
当前开锁器会返回：

```text
option 0: 50 金币
```

如果后续重新启用任务币实验方案，开锁器可能会返回：

```text
option 0: 50 金币 + 25 任务币
```

只有当你的扩展职业明确想完整继承默认杀手商品价格时才用它。  
例如某个杀手职业只是把开锁器换成“高级开锁器”，并且希望它跟随默认开锁器当前完整价格。

不要在中立职业或平民职业里无脑使用完整价格。  
例如黑客是中立职业，正常拿不到任务币，如果直接复制开锁器完整价格，就会导致它无法购买。

### 8.2 读取旧兼容金币价格

```java
int price = ShopApi.getDefaultPrice(WatheItems.LOCKPICK, 50);
```

这个等价于旧 `entry.price()` 逻辑：

- 开锁器返回 50；
- 匕首返回 100；
- 疯魔模式返回 350。

适合：

- 中立职业商店；
- 平民职业商店；
- 只想做纯金币价格的扩展；
- 需要在默认金币价基础上做加减乘除。

### 8.3 精确读取某个 option 的某种货币

推荐使用：

```java
int value = ShopApi.getDefaultCurrencyPrice(
        WatheItems.PSYCHO_MODE,
        1,
        EconomyApi.TASK_MONEY,
        75
);
```

含义：

```text
读取疯魔模式 option 1 里的任务币价格；
如果找不到商品、找不到 option 1、或 option 1 中没有任务币，则返回 fallback 75。
```

快捷方法：

```java
int money0 = ShopApi.getDefaultMoneyPrice(WatheItems.PSYCHO_MODE, 0, 350);
int task0 = ShopApi.getDefaultTaskMoneyPrice(WatheItems.PSYCHO_MODE, 0, 25);
int money1 = ShopApi.getDefaultMoneyPrice(WatheItems.PSYCHO_MODE, 1, 300);
int task1 = ShopApi.getDefaultTaskMoneyPrice(WatheItems.PSYCHO_MODE, 1, 75);
```

对于当前默认疯魔模式，因为任务币实验方案暂停，结果是：

```text
money0 = 350
task0  = 25 // fallback，因为当前 option 0 没有任务币
money1 = 300 // fallback，因为当前没有 option 1
task1  = 75 // fallback，因为当前没有 option 1
```

如果后续重新启用实验方案，疯魔模式会变成：

```text
option 0: 350 金币 + 25 任务币
option 1: 300 金币 + 75 任务币
```

对于当前默认匕首：

```java
int knifeMoney0 = ShopApi.getDefaultMoneyPrice(WatheItems.KNIFE, 0, 100);     // 100
int knifeTask0  = ShopApi.getDefaultTaskMoneyPrice(WatheItems.KNIFE, 0, 0);   // 0，因为 option 0 没有任务币
int knifeMoney1 = ShopApi.getDefaultMoneyPrice(WatheItems.KNIFE, 1, 0);       // 0，因为 option 1 没有金币
int knifeTask1  = ShopApi.getDefaultTaskMoneyPrice(WatheItems.KNIFE, 1, 50);  // 50，当前是 fallback，因为任务币 option 暂停
```

对于当前默认开锁器：

```java
int lockpickMoney0 = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);    // 50
int lockpickTask0  = ShopApi.getDefaultTaskMoneyPrice(WatheItems.LOCKPICK, 0, 25);// 25，当前是 fallback，因为 option 0 没有任务币
```

### 8.4 扩展侧 helper 示例

扩展可以像 NoellesRoles / kinssaba / StupidExpress / StarryExpress 那样包一层 helper。

Yarn 示例：

```java
public static int getDefaultMoneyPrice(Item item, int defaultValue) {
    return ShopApi.getDefaultCurrencyPrice(item, 0, EconomyApi.MONEY, defaultValue);
}

public static int getDefaultCurrencyPrice(
        Item item,
        int optionIndex,
        Identifier currency,
        int defaultValue
) {
    return ShopApi.getDefaultCurrencyPrice(item, optionIndex, currency, defaultValue);
}
```

Mojmap 示例：

```java
public static int getBaseItemPrice(Item item, int defaultValue) {
    return getBaseCurrencyPrice(item, 0, EconomyApi.MONEY, defaultValue);
}

public static int getBaseCurrencyPrice(
        Item item,
        int optionIndex,
        ResourceLocation currency,
        int defaultValue
) {
    return ShopApi.getDefaultCurrencyPrice(item, optionIndex, currency, defaultValue);
}
```

helper 命名建议：

- `getDefaultPrice(...)` 或 `getBaseItemPrice(...)`：默认只读第 0 组金币；
- `getDefaultCurrencyPrice(...)`：细分到某组某货币；
- `getDefaultShopPrice(...)`：返回完整 `ShopPrice`，只在明确完整继承时使用。

## 9. 加减乘除价格应该怎么写

这一节很重要。  
多货币后，不要再把“默认价格”当成一个整体直接做算术。  
你应该先决定要对哪一种货币、哪一个 option 做算术，然后显式重新组装 `ShopPrice`。

### 9.1 中立职业：只读取金币并加价

例如黑客中立职业出售开锁器，但黑客拿不到任务币，所以只读金币：

```java
int lockpickMoney = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);

entries.add(new ShopEntry(
        WatheItems.LOCKPICK.getDefaultStack(),
        lockpickMoney,
        ShopEntry.Type.TOOL
));
```

如果要加价 20：

```java
int lockpickMoney = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);

entries.add(new ShopEntry(
        WatheItems.LOCKPICK.getDefaultStack(),
        lockpickMoney + 20,
        ShopEntry.Type.TOOL
));
```

结果：

```text
70 金币
```

不会包含任务币。

### 9.2 杀手职业：主动采用任务币参考价，并自己改数值

当前 Wathe 默认开锁器是纯金币价格，不再包含任务币。  
下面这个例子表示：你的扩展明确想把 `GameConstants` 注释里保留的“实验任务币方案”当作参考格式重新启用，而不是无意识地复制当前默认价格。

例如某个杀手职业想卖“高级开锁器”，价格设计成参考开锁器的：

- 金币 +20；
- 任务币不变。

```java
int money = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);
int taskMoney = ShopApi.getDefaultTaskMoneyPrice(WatheItems.LOCKPICK, 0, 25);

ShopPrice price = ShopPrice.allOf(
        CurrencyAmount.money(money + 20),
        CurrencyAmount.taskMoney(taskMoney)
);

entries.add(new ShopEntry(
        MyItems.ADVANCED_LOCKPICK.getDefaultStack(),
        price,
        ShopEntry.Type.TOOL
));
```

在当前 Wathe 默认商店中，`taskMoney` 会来自 fallback `25`，因为默认开锁器现在没有任务币价格。  
如果后续 Wathe 重新启用任务币方案，这里会自动读取真实的默认任务币数值。

结果：

```text
70 金币 + 25 任务币
```

### 9.3 对金币做乘法，但不影响任务币

例如制毒师把普通匕首金币价翻倍，但不想自动复制任务币方案：

```java
int knifeMoney = ShopApi.getDefaultMoneyPrice(WatheItems.KNIFE, 0, 100);

entries.add(new ShopEntry(
        WatheItems.KNIFE.getDefaultStack(),
        knifeMoney * 2,
        ShopEntry.Type.WEAPON
));
```

结果：

```text
200 金币
```

不会包含：

```text
50 任务币
```

如果你确实想让它也保留任务币方案，需要显式读取 option 1：

```java
int knifeMoney = ShopApi.getDefaultMoneyPrice(WatheItems.KNIFE, 0, 100);
int knifeTaskMoney = ShopApi.getDefaultTaskMoneyPrice(WatheItems.KNIFE, 1, 50);

ShopPrice price = ShopPrice.anyOf(
        ShopPrice.option(CurrencyAmount.money(knifeMoney * 2)),
        ShopPrice.option(CurrencyAmount.taskMoney(knifeTaskMoney * 2))
);
```

结果：

```text
200 金币
或
100 任务币
```

这是显式设计出来的，而不是自动继承。

### 9.4 对疯魔模式两组方案分别调整

当前 Wathe 默认疯魔模式只有 `option 0 = 350 金币`。  
下面例子适合“扩展职业主动采用实验任务币参考方案”的情况：

- option 0：默认金币 +50，默认任务币不变；
- option 1：默认金币不变，默认任务币 +25。

```java
int money0 = ShopApi.getDefaultMoneyPrice(WatheItems.PSYCHO_MODE, 0, 350);
int task0 = ShopApi.getDefaultTaskMoneyPrice(WatheItems.PSYCHO_MODE, 0, 25);
int money1 = ShopApi.getDefaultMoneyPrice(WatheItems.PSYCHO_MODE, 1, 300);
int task1 = ShopApi.getDefaultTaskMoneyPrice(WatheItems.PSYCHO_MODE, 1, 75);

ShopPrice price = ShopPrice.anyOf(
        ShopPrice.option(
                CurrencyAmount.money(money0 + 50),
                CurrencyAmount.taskMoney(task0)
        ),
        ShopPrice.option(
                CurrencyAmount.money(money1),
                CurrencyAmount.taskMoney(task1 + 25)
        )
);
```

结果：

```text
400 金币 + 25 任务币
或
300 金币 + 100 任务币
```

在当前默认商店中，`task0`、`money1`、`task1` 这几个值会来自你传入的 fallback。  
这代表扩展作者主动采用参考值，而不是 Wathe 当前默认商店真的启用了这些任务币 option。

### 9.5 做减价时要防止负数

例如手雷默认 300 金币，炸弹人便宜 65：

```java
int grenadeMoney = ShopApi.getDefaultMoneyPrice(WatheItems.GRENADE, 0, 300);
int discounted = Math.max(0, grenadeMoney - 65);

entries.add(new ShopEntry(
        WatheItems.GRENADE.getDefaultStack(),
        discounted,
        ShopEntry.Type.WEAPON
));
```

`CurrencyAmount` 不允许负数。  
如果你用 `CurrencyAmount.money(-10)` 会抛异常，所以减价时请使用：

```java
Math.max(0, value - discount)
```

### 9.6 对完整 ShopPrice 做算术时的推荐写法

当前 Wathe 没有提供“自动把整个 ShopPrice 每一种货币都乘 2”的工具方法。  
这是刻意保持保守，因为不同职业对不同货币的设计语义不一定一样。

如果你真的需要“每个 option 的每种货币都乘 2”，可以在扩展里写一个工具：

```java
public static ShopPrice multiplyAllCosts(ShopPrice source, int multiplier) {
    List<ShopPrice.Option> options = new ArrayList<>();

    for (ShopPrice.Option option : source.options()) {
        List<CurrencyAmount> costs = new ArrayList<>();
        for (CurrencyAmount cost : option.costs()) {
            costs.add(CurrencyAmount.of(
                    cost.currency(),
                    Math.max(0, cost.amount() * multiplier)
            ));
        }
        options.add(ShopPrice.option(costs.toArray(CurrencyAmount[]::new)));
    }

    return ShopPrice.anyOf(options.toArray(ShopPrice.Option[]::new));
}
```

但使用前请确认设计含义。

例如，如果后续重新启用实验任务币方案：

```text
匕首参考价：100 金币 或 50 任务币
全部乘 2：200 金币 或 100 任务币
```

这可能合理。

但：

```text
开锁器参考价：50 金币 + 25 任务币
全部乘 2：100 金币 + 50 任务币
```

对某些中立职业可能就不合理，因为它拿不到任务币。

所以推荐优先按“某组 + 某货币”拆开读，再手动组装。

## 10. 什么时候使用完整 ShopPrice，什么时候只读某种货币

### 10.1 适合完整继承 ShopPrice 的情况

使用：

```java
ShopApi.getDefaultShopPrice(item)
```

适合：

- 职业仍然是杀手阵营；
- 玩家能获得默认价格中涉及的所有货币；
- 你想让该商品完全继承 Wathe 默认商品的价格结构；
- 你没有对价格做加减乘除；
- 你明确接受未来 Wathe 默认价格结构改变时，该职业也跟着完整改变。

例子：

```java
ShopPrice lockpickPrice = ShopApi.getDefaultShopPrice(WatheItems.LOCKPICK);
if (lockpickPrice == null) {
    lockpickPrice = ShopPrice.money(50);
}

entries.add(new ShopEntry(
        MyItems.SILVER_LOCKPICK.getDefaultStack(),
        lockpickPrice,
        ShopEntry.Type.TOOL
));
```

### 10.2 适合只读金币的情况

使用：

```java
ShopApi.getDefaultMoneyPrice(item, 0, fallback)
```

适合：

- 中立职业；
- 平民职业；
- 扩展职业没有任务币来源；
- 你要在金币价基础上做加减乘除；
- 你只想让商店保持旧的纯金币体验。

例子：

```java
int lockpickMoney = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);
entries.add(new ShopEntry(WatheItems.LOCKPICK.getDefaultStack(), lockpickMoney, ShopEntry.Type.TOOL));
```

### 10.3 适合细分读取货币的情况

使用：

```java
ShopApi.getDefaultCurrencyPrice(item, optionIndex, currencyId, fallback)
```

适合：

- 你想基于 Wathe 默认价格重新组合一套职业专属价格；
- 你要读取疯魔模式 option 1 的任务币，而不是 option 0；
- 你要对金币和任务币分别加减乘除；
- 你要把默认任务币价格转换成自定义货币价格。

例子：主动采用开锁器实验任务币参考价，并转换成自定义血契币价格：

```java
int money = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);
int taskMoney = ShopApi.getDefaultTaskMoneyPrice(WatheItems.LOCKPICK, 0, 25);

ShopPrice price = ShopPrice.allOf(
        CurrencyAmount.money(money),
        CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, taskMoney)
);
```

当前默认开锁器没有任务币价格，所以 `taskMoney` 会使用 fallback `25`。  
如果 Wathe 后续重新启用开锁器任务币价格，这段代码会改为读取真实默认值。

结果：

```text
50 金币 + 25 血契币
```

## 11. 扩展侧推荐模板

下面是一个较完整的扩展商店工具类模板。

```java
public final class MyModShops {
    private MyModShops() {
    }

    public static int getDefaultMoneyPrice(Item item, int fallback) {
        return ShopApi.getDefaultMoneyPrice(item, 0, fallback);
    }

    public static int getDefaultCurrencyPrice(
            Item item,
            int optionIndex,
            Identifier currency,
            int fallback
    ) {
        return ShopApi.getDefaultCurrencyPrice(item, optionIndex, currency, fallback);
    }

    public static ShopPrice getDefaultShopPriceOrMoney(Item item, int fallback) {
        ShopPrice price = ShopApi.getDefaultShopPrice(item);
        return price == null ? ShopPrice.money(fallback) : price;
    }

    public static RoleShopProvider provider(Function<PlayerEntity, List<ShopEntry>> entriesProvider) {
        return new RoleShopProvider() {
            @Override
            public @NotNull List<ShopEntry> getShopEntries(@NotNull PlayerEntity player) {
                return entriesProvider.apply(player);
            }

            @Override
            public @NotNull ShopPurchaseResult purchase(@NotNull ShopPurchaseContext context) {
                PlayerEntity player = context.player();
                ShopEntry entry = context.entry();
                Item item = entry.stack().getItem();

                if (!context.canAffordEntry() || player.getItemCooldownManager().isCoolingDown(item)) {
                    return ShopPurchaseResult.FAIL_SHOW_MESSAGE;
                }

                return entry.onBuy(player)
                        ? ShopPurchaseResult.SUCCESS
                        : ShopPurchaseResult.FAIL_SHOW_MESSAGE;
            }
        };
    }
}
```

非杀手职业的 `entry.onBuy(player)` 默认会失败，所以非杀手职业请使用：

```java
ShopEntry.giveToInventory(...)
ShopEntry.directToHotbar(...)
ShopEntry.action(...)
```

或者在 `purchase(...)` 中调用自己的交付逻辑。

## 12. 常见问题和坑

### 12.1 为什么我的中立职业买不了开锁器？

如果你这样写：

```java
new ShopEntry(
        WatheItems.LOCKPICK.getDefaultStack(),
        ShopApi.getDefaultShopPrice(WatheItems.LOCKPICK),
        ShopEntry.Type.TOOL
)
```

在当前 Wathe 默认设置下，你复制到的是完整默认价格：

```text
50 金币
```

它现在可以买。  
但这个写法仍然不推荐给中立职业，因为一旦 Wathe 或其它整合包重新启用任务币实验方案，完整默认价格可能变回：

```text
50 金币 + 25 任务币
```

中立职业通常拿不到任务币，所以即使金币足够也无法购买。

应改成：

```java
int lockpickMoney = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);

new ShopEntry(
        WatheItems.LOCKPICK.getDefaultStack(),
        lockpickMoney,
        ShopEntry.Type.TOOL
)
```

### 12.2 为什么 `entry.price()` 只返回金币？

`entry.price()` 是旧兼容接口，只能返回一个 `int`。  
多货币价格不能完整塞进一个 `int`，所以它只返回 legacy price。

新代码读取完整价格：

```java
entry.shopPrice()
```

新代码判断能否购买：

```java
entry.shopPrice().canAfford(shop)
context.canAffordEntry()
```

### 12.3 为什么我用完整 ShopPrice 后，商品多出了任务币要求？

因为完整 `ShopPrice` 会保留所有货币条件。  
当前 Wathe 默认商店已经恢复为纯金币，所以本体默认开锁器现在不会多出任务币要求。  
但如果后续 Wathe 重新启用任务币方案，或者整合包 / 扩展把默认商品改成 `50 金币 + 25 任务币`，你完整复制后仍然会复制到这个完整价格。

如果你只想要金币，请读金币：

```java
ShopApi.getDefaultMoneyPrice(item, 0, fallback)
```

如果你只想要任务币，请读任务币：

```java
ShopApi.getDefaultTaskMoneyPrice(item, optionIndex, fallback)
```

如果你想重新组合，请拆开读取后自己组装。

### 12.4 为什么任务币 HUD 没显示？

Wathe 本体当前暂停了任务币实验玩法：默认杀手商店是纯金币，杀手任务 / 击杀任务币收益为 `0`，并且 `EconomyApi` 里任务币 HUD 注册逻辑暂时注释掉了。  
如果你只是运行当前 Wathe 本体，这是预期行为。

如果你在扩展模组里重新注册了任务币或自定义货币 HUD，再检查：

1. 玩家是否拥有任务币余额，余额必须 `> 0`；
2. 玩家是否满足货币注册时的 HUD predicate；
3. 客户端是否加载了任务币图标字体；
4. 如果刚消费到 0，HUD 会先滚动到 0 再淡出，这是正常行为。

### 12.5 为什么购买成功后没有扣钱？

如果你使用 Wathe 新的 `ShopApi` 购买流程，扣钱发生在 `PlayerShopComponent#tryBuy(...)` 中。  
provider 的 `purchase(...)` 只应该返回成功或失败。

如果你绕过了 Wathe 的购买流程，自己调用 `deliverPurchasedStack`，那 Wathe 不会自动扣钱。  
推荐不要绕过 `ShopApi`。

### 12.6 为什么购买失败提示重复或音效重复？

扩展 provider 不应该自己播放通用音效或发送通用失败提示。  
返回：

```java
ShopPurchaseResult.FAIL_SHOW_MESSAGE
ShopPurchaseResult.FAIL_SILENT
ShopPurchaseResult.SUCCESS
```

让 Wathe 统一处理提示和音效。

特殊即时商品如果自己已经发了更具体的提示，可以返回：

```java
ShopPurchaseResult.FAIL_SILENT
```

### 12.7 为什么替换默认商品后丢失任务币价格？

如果你这样替换：

```java
entries.set(index, new ShopEntry(MyItems.NEW_ITEM.getDefaultStack(), 50, ShopEntry.Type.TOOL));
```

这就是纯金币价格。

如果你想完整继承默认商品价格：

```java
ShopPrice price = ShopApi.getDefaultShopPrice(WatheItems.LOCKPICK);
if (price == null) {
    price = ShopPrice.money(50);
}

entries.set(index, new ShopEntry(MyItems.NEW_ITEM.getDefaultStack(), price, ShopEntry.Type.TOOL));
```

如果你只想继承默认金币价：

```java
int money = ShopApi.getDefaultMoneyPrice(WatheItems.LOCKPICK, 0, 50);
entries.set(index, new ShopEntry(MyItems.NEW_ITEM.getDefaultStack(), money, ShopEntry.Type.TOOL));
```

## 13. 推荐实践总结

### 13.1 给扩展作者的规则

1. 新货币先注册 `EconomyApi.registerCurrency(...)`。
2. 玩家余额统一通过 `PlayerShopComponent#getCurrencyAmount / addCurrencyAmount / setCurrencyAmount` 读写。
3. 商店价格统一用 `ShopPrice` 表达，不要自己写扣款逻辑。
4. provider 的 `purchase(...)` 只负责交付商品是否成功，不负责扣钱、音效、同步。
5. 判断能否购买用 `context.canAffordEntry()`。
6. 中立/平民职业默认只读取金币价格，不要复制完整默认杀手价格。
7. 要读取默认价格时，优先使用：

```java
ShopApi.getDefaultMoneyPrice(item, optionIndex, fallback)
ShopApi.getDefaultTaskMoneyPrice(item, optionIndex, fallback)
ShopApi.getDefaultCurrencyPrice(item, optionIndex, currency, fallback)
```

8. 只有明确要完整继承默认价格结构时，才使用：

```java
ShopApi.getDefaultShopPrice(item)
```

9. 对价格做加减乘除时，先拆到“某个 option 的某种货币”，再显式重新组装。
10. `entry.price()` 只用于旧兼容，不用于新多货币逻辑。

### 13.2 简单决策表

| 场景 | 推荐 API |
| --- | --- |
| 纯金币商品 | `new ShopEntry(stack, int, type)` 或 `ShopPrice.money(int)` |
| 金币 + 任务币 | `ShopPrice.allOf(CurrencyAmount.money(...), CurrencyAmount.taskMoney(...))` |
| 金币或任务币 | `ShopPrice.anyOf(option(money), option(taskMoney))` |
| 自定义货币 | `CurrencyAmount.of(MyCurrency.ID, amount)` |
| 判断能不能买 | `context.canAffordEntry()` |
| 读取玩家某货币余额 | `context.currencyBalance(currency)` 或 `shop.getCurrencyAmount(currency)` |
| 读取默认第 0 组金币价 | `ShopApi.getDefaultMoneyPrice(item, 0, fallback)` |
| 读取默认第 1 组任务币价 | `ShopApi.getDefaultTaskMoneyPrice(item, 1, fallback)` |
| 读取默认某组某自定义货币 | `ShopApi.getDefaultCurrencyPrice(item, option, currency, fallback)` |
| 完整继承默认价格 | `ShopApi.getDefaultShopPrice(item)` |
| 非杀手职业发商品 | `ShopEntry.giveToInventory` / `directToHotbar` / 自定义 provider 交付 |

## 14. 最小完整示例

下面是一个“血契币 + 血法师商店”的简化示例。

### 14.1 注册货币

```java
public final class MyEconomy {
    public static final Identifier BLOOD_TOKEN = MyMod.id("blood_token");
    public static final String BLOOD_TOKEN_ICON = "\uE783";

    public static void init() {
        EconomyApi.registerCurrency(
                BLOOD_TOKEN,
                BLOOD_TOKEN_ICON,
                "currency.mymod.blood_token",
                context -> context.role() == MyRoles.BLOOD_MAGE
        );
    }
}
```

### 14.2 发放货币

```java
TaskCompletionApi.AFTER_TASK_COMPLETE.register(context -> {
    if (context.role() == MyRoles.BLOOD_MAGE) {
        PlayerShopComponent.KEY.get(context.player()).addCurrencyAmount(MyEconomy.BLOOD_TOKEN, 1);
    }
});
```

### 14.3 注册商店

```java
public final class BloodMageShop {
    private static final List<ShopEntry> ENTRIES = List.of(
            ShopEntry.giveToInventory(
                    MyItems.BLOOD_DAGGER.getDefaultStack(),
                    ShopPrice.anyOf(
                            ShopPrice.option(CurrencyAmount.money(200)),
                            ShopPrice.option(CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 3))
                    ),
                    ShopEntry.Type.WEAPON
            ),
            ShopEntry.giveToInventory(
                    MyItems.BLOOD_BOMB.getDefaultStack(),
                    ShopPrice.allOf(
                            CurrencyAmount.money(100),
                            CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, 2)
                    ),
                    ShopEntry.Type.TOOL
            )
    );

    public static void init() {
        ShopApi.registerStaticRoleShop(MyRoles.BLOOD_MAGE, () -> ENTRIES);
    }
}
```

### 14.4 读取默认价格并改造

血法师想出售“血契疯魔”，基于 Wathe 疯魔模式 option 1：

```java
int money = ShopApi.getDefaultMoneyPrice(WatheItems.PSYCHO_MODE, 1, 300);
int taskMoney = ShopApi.getDefaultTaskMoneyPrice(WatheItems.PSYCHO_MODE, 1, 75);

ShopPrice bloodPsychoPrice = ShopPrice.allOf(
        CurrencyAmount.money(Math.max(0, money - 50)),
        CurrencyAmount.of(MyEconomy.BLOOD_TOKEN, Math.max(1, taskMoney / 25))
);

ShopEntry bloodPsycho = ShopEntry.action(
        WatheItems.PSYCHO_MODE.getDefaultStack(),
        bloodPsychoPrice,
        ShopEntry.Type.WEAPON,
        PlayerShopComponent::usePsychoMode
);
```

这里没有直接复制完整默认 `ShopPrice`，而是：

```text
读取疯魔模式 option 1 的金币 300 -> 变成 250 金币
读取疯魔模式 option 1 的任务币 75 -> 转换成 3 血契币
最后重新组装成 250 金币 + 3 血契币
```

这种写法最清晰，也最不容易误伤其它职业经济设计。
