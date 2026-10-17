package dev.doctor4t.wathe.game;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface GameConstants {
    // Logistics
    int FADE_TIME = 40;
    int FADE_PAUSE = 20;

    /**
     * 玩家连续变化状态（例如体力、心情数值）的网络校准周期。
     *
     * <p>服务端玩法仍然按每 tick 精确计算，客户端只需要定期拿到最新值即可。
     * 结构变化和临界值变化仍由对应组件立即同步，因此这个周期不会改变服务端裁定，
     * 只减少“客户端可以短暂本地显示旧值”的重复组件包。</p>
     */
    int PLAYER_STATE_SYNC_INTERVAL_TICKS = 5;

    /**
     * 疯魔倒计时的客户端校准周期。疯魔组件的客户端 tick 会自行递减倒计时，
     * 服务端每秒校准一次即可，启动和结束仍然立即同步。
     */
    int PSYCHO_SYNC_INTERVAL_TICKS = 20;

    /**
     * 普通玩法开局后计时器的默认时长，单位：tick。
     *
     * <p>当 /wathe:start 没有手动传入时间、自动开局或喇叭开局时，
     * Murder / Discovery 等模式会通过 GameMode.defaultStartTime 读取这里。
     * 想微调默认时长，直接改 getInTicks(分钟, 秒) 的两个参数即可。</p>
     */
    int DEFAULT_GAME_START_TIME = getInTicks(8, 30);

    /**
     * Murder 模式自身允许调试开局的硬性最低人数。
     *
     * <p>这里和“正式默认开局人数”分开：模式硬底线为 1，方便管理员用
     * /wathe:startPlayerCount 调低后开测试局；正式默认门槛见
     * {@link #DEFAULT_MURDER_START_PLAYER_COUNT}。</p>
     */
    int MIN_MURDER_PLAYER_COUNT = 1;
    /**
     * Discovery 探索模式自身允许调试开局的硬性最低人数。
     */
    int MIN_DISCOVERY_PLAYER_COUNT = 1;
    /**
     * Loose Ends / 孤行者模式至少需要 2 人，否则“最后一人存活”的核心规则没有意义。
     */
    int MIN_LOOSE_ENDS_PLAYER_COUNT = 2;

    /**
     * Murder 模式默认允许开局的准备区人数。
     */
    int DEFAULT_MURDER_START_PLAYER_COUNT = 6;
    /**
     * HarpyModLoader 的 modded murder 模式默认允许开局的准备区人数。
     *
     * <p>Wathe 不直接依赖 Harpy 工程，因此这里只保存它稳定使用的 game mode id。
     * Harpy 加载后会把 {@code harpymodloader:modded} 注册进 WatheGameModes，指令即可单独调整。</p>
     */
    int DEFAULT_HARPY_MODDED_START_PLAYER_COUNT = 6;
    /**
     * Discovery 探索模式默认 1 人即可启动，方便单人看图和调试地图。
     */
    int DEFAULT_DISCOVERY_START_PLAYER_COUNT = 1;
    /**
     * Loose Ends / 孤行者模式默认 2 人即可启动。
     */
    int DEFAULT_LOOSE_ENDS_START_PLAYER_COUNT = 2;
    /**
     * 未知扩展模式的默认开局人数兜底。
     *
     * <p>扩展模式如果没有单独注册默认值，会先走这个兜底；管理员仍然可以通过
     * /wathe:startPlayerCount mode <gameMode> <players> 单独覆盖。</p>
     */
    int DEFAULT_UNKNOWN_MODE_START_PLAYER_COUNT = DEFAULT_MURDER_START_PLAYER_COUNT;

    Identifier HARPY_MODDED_GAME_MODE_ID = Identifier.of("harpymodloader", "modded");
    Map<Identifier, Integer> DEFAULT_START_PLAYER_COUNTS = Util.make(new HashMap<>(), defaults -> {
        defaults.put(Wathe.id("murder"), DEFAULT_MURDER_START_PLAYER_COUNT);
        defaults.put(HARPY_MODDED_GAME_MODE_ID, DEFAULT_HARPY_MODDED_START_PLAYER_COUNT);
        defaults.put(Wathe.id("discovery"), DEFAULT_DISCOVERY_START_PLAYER_COUNT);
        defaults.put(Wathe.id("loose_ends"), DEFAULT_LOOSE_ENDS_START_PLAYER_COUNT);
    });

    static int getDefaultStartPlayerCount(@Nullable Identifier gameModeId) {
        if (gameModeId == null) {
            return DEFAULT_UNKNOWN_MODE_START_PLAYER_COUNT;
        }
        return DEFAULT_START_PLAYER_COUNTS.getOrDefault(gameModeId, DEFAULT_UNKNOWN_MODE_START_PLAYER_COUNT);
    }

    // Blocks
    int DOOR_AUTOCLOSE_TIME = getInTicks(0, 5);

    // Items
    Map<Item, Integer> ITEM_COOLDOWNS = new HashMap<>();
    /**
     * 左轮的“义警阵营”冷却。
     *
     * <p>这里开始不再把左轮视为“全职业统一 10 秒冷却”的物品，
     * 而是把每个阵营的基础冷却单独拆成常量，方便后续继续微调数值。</p>
     */
    int REVOLVER_COOLDOWN_VIGILANTE = getInTicks(0, 8);
    /**
     * 左轮的“平民阵营”冷却。
     */
    int REVOLVER_COOLDOWN_CIVILIAN = getInTicks(0, 12);
    /**
     * 左轮的“杀手阵营”冷却。
     */
    int REVOLVER_COOLDOWN_KILLER = getInTicks(0, 15);
    /**
     * 左轮的“中立阵营”冷却。
     *
     * <p>这里的“中立”专指：既不是平民阵营，也不是杀手阵营的职业。</p>
     */
    int REVOLVER_COOLDOWN_NEUTRAL = getInTicks(0, 20);
    /**
     * 左轮的静态兜底冷却。
     *
     * <p>兼容层仍然会把左轮写入 {@link #ITEM_COOLDOWNS}，
     * 以照顾仍在读取固定冷却表的旧代码 / 旧工具提示逻辑。
     * 但真正的左轮开火冷却，统一应走 {@link #getRevolverCooldown(Role)}
     * 或 {@link #getRevolverCooldown(PlayerEntity)}。</p>
     */
    int REVOLVER_COOLDOWN_FALLBACK = REVOLVER_COOLDOWN_CIVILIAN;

    static void init() {
        /*
         * 这里的 35 秒是 Wathe 默认匕首冷却的基础值。
         * 真正刺杀后写入 ItemCooldownManager 前，KnifeStabPayload 会把它交给
         * KnifeCooldownApi 的优先级流水线修正；扩展不应直接 mixin payload 覆写该值。
         */
        ITEM_COOLDOWNS.put(WatheItems.KNIFE, getInTicks(0, 35));
        ITEM_COOLDOWNS.put(WatheItems.REVOLVER, REVOLVER_COOLDOWN_FALLBACK);
        ITEM_COOLDOWNS.put(WatheItems.DERRINGER, getInTicks(0, 1));
        ITEM_COOLDOWNS.put(WatheItems.GRENADE, getInTicks(0, 0));
        ITEM_COOLDOWNS.put(WatheItems.LOCKPICK, getInTicks(2, 40));
        ITEM_COOLDOWNS.put(WatheItems.CROWBAR, getInTicks(0, 10));
        ITEM_COOLDOWNS.put(WatheItems.BODY_BAG, getInTicks(0, 30));
        ITEM_COOLDOWNS.put(WatheItems.PSYCHO_MODE, getInTicks(4, 30));
        ITEM_COOLDOWNS.put(WatheItems.BLACKOUT, getInTicks(3, 0));
    }

    int JAMMED_DOOR_TIME = getInTicks(1, 0);

    // Corpses尸体
    int TIME_TO_DECOMPOSITION = getInTicks(1, 0);//保持原状
    int DECOMPOSING_TIME = getInTicks(4, 0);//尸体分解

    // 心情任务相关常量
    float MOOD_GAIN = 0.4f;
    float MOOD_DRAIN = 1f / getInTicks(3, 20);
    int TIME_TO_FIRST_TASK = getInTicks(0, 30);
    int MIN_TASK_COOLDOWN = getInTicks(0, 30);
    int MAX_TASK_COOLDOWN = getInTicks(1, 0);
    /**
     * 随机任务事件模式下，首个任务之后每次随机事件允许发放任务的最短间隔。
     *
     * <p>注意：随机模式的开局首个任务仍然使用 {@link #TIME_TO_FIRST_TASK}，
     * 只有首个任务已经成功发放后，后续事件才会使用这里的 35 秒下限。</p>
     */
    int RANDOM_MOOD_TASK_MIN_COOLDOWN = getInTicks(0, 35);
    /**
     * 随机任务事件模式下，首个任务之后每次随机事件允许发放任务的最长间隔。
     */
    int RANDOM_MOOD_TASK_MAX_COOLDOWN = getInTicks(0, 55);
    /**
     * 多任务心情扣除倍率开关的默认状态。
     * 关闭时保持原有的单任务固定心情下降速度。
     */
    boolean DEFAULT_MOOD_TASK_DRAIN_MULTIPLIER_ENABLED = false;
    /**
     * 任务发放模式的默认状态。
     * false 表示使用原有的固定心情阈值补任务模式，true 表示使用随机事件模式。
     */
    boolean DEFAULT_RANDOM_MOOD_TASK_SPAWN_ENABLED = false;
    /**
     * 运行时最大任务槽位允许设置的最低值，避免管理员把任务系统设置成完全不可发放。
     */
    int MIN_CONFIGURED_MOOD_TASK_SLOTS = 1;
    /**
     * 新世界和旧存档缺少配置字段时使用的默认任务槽位数量。
     * 原有玩法默认最多同时存在 3 个任务，因此这里保持为 3。
     */
    int DEFAULT_CONFIGURED_MOOD_TASK_SLOTS = 3;
    /**
     * 卡死任务自动清理机制的默认状态。
     * 默认开启，兼容当前已有的 4 次 / 6 次清理规则。
     */
    boolean DEFAULT_STUCK_TASK_CLEANUP_ENABLED = true;
    /**
     * 旧版公开常量，保留给扩展源码和旧二进制兼容使用。
     * 实际运行时上限由 GameWorldComponent 的任务槽位设置决定，允许高于这个历史默认值。
     */
    int MAX_CONCURRENT_MOOD_TASKS = DEFAULT_CONFIGURED_MOOD_TASK_SLOTS;
    float SECOND_TASK_MOOD_THRESHOLD = 0.51f;
    float THIRD_TASK_MOOD_THRESHOLD = 0.17f;
    float MOOD_BREAKDOWN_WARNING_THRESHOLD = 0.15f;
    int TASK_COMPLETIONS_TO_CLEAR_ONE_STUCK_TASK = 4;
    int TASK_COMPLETIONS_TO_CLEAR_TWO_STUCK_TASKS = 6;
    float FIRE_TASK_RANGE = 2.0f;
    float STARE_TASK_RANGE = 2.0f;
    float AWAY_TASK_RANGE = 12.0f;
    /**
     * “泡水”任务点透视时，允许被记录的水域最大高度（按连通水域的 Y 轴层数计算）。
     *
     * <p>例如：
     * 1. 设为 1，则只透视“深度 / 高度为 1 格”的浅水区域；
     * 2. 设为 2，则允许两层高的连通水域；
     * 3. 设为 -1，则关闭“高度限制”。
     */
    int WATER_TASK_POINT_MAX_HEIGHT = 1;

    /**
     * “泡水”任务点透视时，允许被记录的连通水域最大格数。
     *
     * <p>例如：
     * 1. 设为 8，则最多只透视 8 格以内的小水池；
     * 2. 更大的连通水域（例如河流、大池塘）会整块跳过，不渲染任何透视点；
     * 3. 设为 -1，则关闭“面积 / 连通格数限制”。
     */
    int WATER_TASK_POINT_MAX_CONNECTED_BLOCKS = 8;

    // 所有累计类任务默认都需要累计 8 秒。
    int SLEEP_TASK_DURATION = getInTicks(0, 8);
    int OUTSIDE_TASK_DURATION = getInTicks(0, 8);
    int WATER_TASK_DURATION = getInTicks(0, 8);
    int FIRE_TASK_DURATION = getInTicks(0, 8);
    int SHIFT_TASK_DURATION = getInTicks(0, 8);
    int STARE_TASK_DURATION = getInTicks(0, 8);
    int AWAY_TASK_DURATION = getInTicks(0, 8);
    int RUN_TASK_DURATION = getInTicks(0, 8);
    int SIT_TASK_DURATION = getInTicks(0, 8);
    int BOOK_TASK_DURATION = getInTicks(0, 8);
    int STAY_TASK_DURATION = getInTicks(0, 8);
    int MUSIC_TASK_COUNT = 10;

    float MID_MOOD_THRESHOLD = 0.55f;
    float DEPRESSIVE_MOOD_THRESHOLD = 0.2f;
    /**
     * 心情濒临崩溃时 HUD 抖动的“标准差”。
     *
     * <p>这里使用高斯噪声而不是方形随机，能让抖动看起来更接近 Spark 的手感：
     * 1. 中心位置更稳定，不会像随机方块一样左右跳格子；
     * 2. 偶尔会出现稍大的自然偏移，让“崩溃前摇”更有呼吸感。
     */
    float MOOD_BREAKDOWN_SHAKE_STDDEV = 3.0f;
    /**
     * HUD 抖动的最大偏移保护值，避免高斯分布的极端样本把图标或文字甩得过远。
     *
     * <p>这个值只是视觉兜底，不会影响正常的抖动核心手感。</p>
     */
    float MOOD_BREAKDOWN_SHAKE_MAX_OFFSET = 6.0f;

    // 玩家移动 / 体力基础数值
    float BASE_WALK_MOVEMENT_SPEED = 0.07f;
    float BASE_SPRINT_MOVEMENT_SPEED = 0.1f;
    /**
     * 高心情或未启用心情体力惩罚时，疾跑每 tick 消耗的体力。
     */
    float STAMINA_SPRINT_DRAIN_HIGH_MOOD = 1.0f;
    /**
     * 高心情或未启用心情体力惩罚时，非疾跑每 tick 恢复的体力。
     */
    float STAMINA_RECOVERY_HIGH_MOOD = 0.8f;
    /**
     * 中等心情惩罚开启后，疾跑每 tick 消耗的体力。
     *
     * <p>该阶段不让走路消耗体力：走路和静止都按恢复处理。</p>
     */
    float STAMINA_SPRINT_DRAIN_MID_MOOD = 1.2f;
    float STAMINA_RECOVERY_MID_MOOD = 0.6f;
    /**
     * 低落心情惩罚开启后，玩家不能疾跑；这里保留疾跑消耗常量，
     * 是为了后续如果要允许特殊职业突破禁跑，也能沿用同一套调参入口。
     */
    float STAMINA_SPRINT_DRAIN_DEPRESSIVE_MOOD = 1.5f;
    float STAMINA_WALK_DRAIN_DEPRESSIVE_MOOD = 1.5f;
    float STAMINA_RECOVERY_DEPRESSIVE_MOOD = 0.4f;
    /**
     * 两个心情体力惩罚默认关闭，必须通过调试/配置指令显式打开。
     */
    boolean DEFAULT_MID_MOOD_STAMINA_PENALTY_ENABLED = false;
    boolean DEFAULT_DEPRESSIVE_MOOD_STAMINA_PENALTY_ENABLED = false;
    float ITEM_PSYCHOSIS_CHANCE = .5f; // in percent
    int ITEM_PSYCHOSIS_REROLL_TIME = 200;

    // Shop Variables
    int SHOP_KNIFE_MONEY_PRICE = 100;
    int SHOP_KNIFE_TASK_MONEY_PRICE = 50;
    int SHOP_PSYCHO_MODE_MONEY_PRICE_PRIMARY = 350;
    int SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_PRIMARY = 25;
    int SHOP_PSYCHO_MODE_MONEY_PRICE_SECONDARY = 300;
    int SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_SECONDARY = 75;
    int SHOP_LOCKPICK_MONEY_PRICE = 50;
    int SHOP_LOCKPICK_TASK_MONEY_PRICE = 25;
    /** 商店调试开关默认关闭：普通商品购买仍受物品冷却限制。 */
    boolean DEFAULT_SHOP_ITEM_COOLDOWN_BYPASS_ENABLED = false;
    // 任务币目前作为杀手商店货币仍处于实验性暂停阶段；0 表示禁用任务完成后的任务币奖励。
    int TASK_MONEY_PER_KILLER_TASK = 0;
    List<ShopEntry> SHOP_ENTRIES = Util.make(new ArrayList<>(), entries -> {
        /*
         * 任务币商店价格方案暂时暂停。
         *
         * 原因：任务币作为杀手商店货币仍在实验阶段，当前同时平衡金币价格、任务币价格、
         * 以及“金币 + 任务币 / 金币或任务币”的多方案交易会让默认杀手商店难以调数值。
         * 因此当前实际商店先恢复为纯金币价格：匕首 100 金币、疯魔模式 350 金币、开锁器 50 金币。
         *
         * 后续如果要重新启用任务币价格，可以参考下面格式：
         *
         * 匕首：100 金币 或 50 任务币
         * new ShopEntry(
         *         WatheItems.KNIFE.getDefaultStack(),
         *         ShopPrice.anyOf(
         *                 ShopPrice.option(CurrencyAmount.money(SHOP_KNIFE_MONEY_PRICE)),
         *                 ShopPrice.option(CurrencyAmount.taskMoney(SHOP_KNIFE_TASK_MONEY_PRICE))
         *         ),
         *         ShopEntry.Type.WEAPON
         * );
         *
         * 疯魔模式：350 金币 + 25 任务币，或 300 金币 + 75 任务币
         * new ShopEntry(
         *         WatheItems.PSYCHO_MODE.getDefaultStack(),
         *         ShopPrice.anyOf(
         *                 ShopPrice.option(
         *                         CurrencyAmount.money(SHOP_PSYCHO_MODE_MONEY_PRICE_PRIMARY),
         *                         CurrencyAmount.taskMoney(SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_PRIMARY)
         *                 ),
         *                 ShopPrice.option(
         *                         CurrencyAmount.money(SHOP_PSYCHO_MODE_MONEY_PRICE_SECONDARY),
         *                         CurrencyAmount.taskMoney(SHOP_PSYCHO_MODE_TASK_MONEY_PRICE_SECONDARY)
         *                 )
         *         ),
         *         ShopEntry.Type.WEAPON
         * );
         *
         * 开锁器：50 金币 + 25 任务币
         * new ShopEntry(
         *         WatheItems.LOCKPICK.getDefaultStack(),
         *         ShopPrice.allOf(
         *                 CurrencyAmount.money(SHOP_LOCKPICK_MONEY_PRICE),
         *                 CurrencyAmount.taskMoney(SHOP_LOCKPICK_TASK_MONEY_PRICE)
         *         ),
         *         ShopEntry.Type.TOOL
         * );
         */
        entries.add(new ShopEntry(WatheItems.KNIFE.getDefaultStack(), SHOP_KNIFE_MONEY_PRICE, ShopEntry.Type.WEAPON));
        entries.add(new ShopEntry(WatheItems.REVOLVER.getDefaultStack(), 250, ShopEntry.Type.WEAPON));
        entries.add(new ShopEntry(WatheItems.GRENADE.getDefaultStack(), 300, ShopEntry.Type.WEAPON));
        entries.add(ShopEntry.action(
                WatheItems.PSYCHO_MODE.getDefaultStack(),
                SHOP_PSYCHO_MODE_MONEY_PRICE_PRIMARY,
                ShopEntry.Type.WEAPON,
                PlayerShopComponent::usePsychoMode
        ));
        entries.add(new ShopEntry(WatheItems.POISON_VIAL.getDefaultStack(), 70, ShopEntry.Type.POISON));
        entries.add(new ShopEntry(WatheItems.SCORPION.getDefaultStack(), 40, ShopEntry.Type.POISON));
        entries.add(new ShopEntry(WatheItems.FIRECRACKER.getDefaultStack(), 10, ShopEntry.Type.TOOL));
        entries.add(new ShopEntry(WatheItems.LOCKPICK.getDefaultStack(), SHOP_LOCKPICK_MONEY_PRICE, ShopEntry.Type.TOOL));
        entries.add(new ShopEntry(WatheItems.CROWBAR.getDefaultStack(), 25, ShopEntry.Type.TOOL));
        entries.add(new ShopEntry(WatheItems.BODY_BAG.getDefaultStack(), 70, ShopEntry.Type.TOOL));
        entries.add(ShopEntry.action(
                WatheItems.BLACKOUT.getDefaultStack(),
                250,
                ShopEntry.Type.TOOL,
                PlayerShopComponent::useBlackout
        ));
        entries.add(new ShopEntry(new ItemStack(WatheItems.NOTE, 4), 10, ShopEntry.Type.TOOL));
    });
    int MONEY_START = 120;
    Function<Long, Integer> PASSIVE_MONEY_TICKER = time -> {
        if (time % getInTicks(0, 10) == 0) {
            return 5;
        }
        return 0;
    };
    /**
     * 杀手阵营的被动收入金币上限。
     *
     * <p>当玩家当前金币达到或超过该值后，将不再继续获得被动收入。
     * 如果这里配置为负数，则表示关闭“杀手阵营被动收入上限”。</p>
     */
    int PASSIVE_MONEY_CAP_KILLER = -1;
    /**
     * 中立阵营的被动收入金币上限。
     *
     * <p>当玩家当前金币达到或超过该值后，将不再继续获得被动收入。
     * 如果这里配置为负数，则表示关闭“中立阵营被动收入上限”。</p>
     */
    int PASSIVE_MONEY_CAP_NEUTRAL = -1;
    /**
     * 好人阵营的被动收入金币上限。
     *
     * <p>这里统一覆盖平民阵营与义警阵营，
     * 以及未来所有注册到这两个阵营下、并且拥有被动收入能力的扩展职业。
     * 如果这里配置为负数，则表示关闭“好人阵营被动收入上限”。</p>
     */
    int PASSIVE_MONEY_CAP_PASSENGER = -1;
    //杀手每杀一个人获得的金币
    int MONEY_PER_KILL = 125;
    // 任务币击杀收益随任务币商店实验一起暂停；0 表示禁用击杀后的任务币奖励，后续重新启用时再调为正数。
    int TASK_MONEY_PER_KILL = 0;
    //疯魔状态下初始护盾数量
    int PSYCHO_MODE_ARMOUR = 1;

    /// 相关时间常量
    //疯魔模式时间
    int PSYCHO_TIMER = getInTicks(0, 45);
    //鞭炮放置后准备爆炸的时间
    int FIRECRACKER_TIMER = getInTicks(0, 15);
    //停电最短恢复时间
    int BLACKOUT_MIN_DURATION = getInTicks(0, 5);
    //停电最长恢复时间
    int BLACKOUT_MAX_DURATION = getInTicks(0, 20);
    /**
     * 自然恢复时，同一个区块分段内一次最多恢复多少盏灯。
     *
     * <p>停电灯会按 16x16x16 的 ChunkSection 分组。同一分段内的更新放在同一个服务端 tick，
     * 客户端便可以合并方块同步并只安排一次分段网格重建；但单批也不能无限大，
     * 否则极端高密度灯墙仍可能在一个 tick 内制造明显尖峰。</p>
     */
    int BLACKOUT_RECOVERY_MAX_LIGHTS_PER_BATCH = 64;
    /**
     * 每个服务端 tick 最多处理多少个停电恢复批次。
     *
     * <p>这里优先保证稳定帧时间。恢复窗口很短、灯又异常多时，未完成的批次允许在
     * {@link #BLACKOUT_MAX_DURATION} 之后继续少量 tick，而不是为了死守截止时间一次性恢复全部灯。</p>
     */
    int BLACKOUT_RECOVERY_MAX_BATCHES_PER_TICK = 2;
    /**
     * 单个服务端 tick 可用于恢复灯光的软时间预算，单位为纳秒。
     *
     * <p>预算会在每个完整区块分段批次结束后检查，避免拆散同一批方块、导致客户端重复重建；
     * 因此它是防止继续追加新批次的软上限，不会中途打断已经开始的批次。</p>
     */
    long BLACKOUT_RECOVERY_MAX_NANOS_PER_TICK = 2_000_000L;
    /**
     * 停电进入恢复阶段后，全局恢复灯音效下一次播放的最短间隔，单位为 tick。
     *
     * <p>间隔不能过短，否则地图灯很多时虽然没有方块更新压力，也会让所有玩家持续听到
     * 密集重复的声音。默认 0.25 秒是一个能感知“正在恢复”又不会喧宾夺主的下限。</p>
     */
    int BLACKOUT_RECOVERY_SOUND_MIN_INTERVAL = getInTicks(0, 0)+5;
    /**
     * 停电进入恢复阶段后，全局恢复灯音效下一次播放的最长间隔，单位为 tick。
     * 每次播放后都会在这个范围内重新随机，直到所有灯恢复完成。
     */
    int BLACKOUT_RECOVERY_SOUND_MAX_INTERVAL = getInTicks(0, 2);
    //每杀一个平民所增加的时间
    int TIME_ON_CIVILIAN_KILL = getInTicks(0, 45);

    /**
     * 根据本局心情推进到的最低值，计算应该解锁的任务槽位数。
     * 第一个任务槽始终存在；第二、第三个槽位会在心情跌到对应阈值后解锁。
     */
    static int getUnlockedMoodTaskSlots(float mood) {
        int slots = 1;
        if (mood <= SECOND_TASK_MOOD_THRESHOLD) {
            slots = 2;
        }
        if (mood <= THIRD_TASK_MOOD_THRESHOLD) {
            slots = 3;
        }
        return Math.min(slots, MAX_CONCURRENT_MOOD_TASKS);
    }

    /**
     * 判断是否到达第二个任务槽位的解锁阈值。
     */
    static boolean shouldUnlockSecondMoodTask(float mood) {
        return mood <= SECOND_TASK_MOOD_THRESHOLD;
    }

    /**
     * 判断是否到达第三个任务槽位的解锁阈值。
     */
    static boolean shouldUnlockThirdMoodTask(float mood) {
        return mood <= THIRD_TASK_MOOD_THRESHOLD;
    }

    /**
     * 判断当前心情是否已经进入濒死预警阶段。
     */
    static boolean shouldShowMoodBreakdownWarning(float mood) {
        return mood <= MOOD_BREAKDOWN_WARNING_THRESHOLD;
    }

    /**
     * 根据阵营返回左轮应当进入的冷却时长。
     *
     * <p>这样后续无论是本体职业，还是扩展模组注册进来的职业，
     * 只要阵营定义正确，左轮就会自动吃到对应阵营的冷却。</p>
     */
    static int getRevolverCooldown(@Nullable Faction faction) {
        if (faction == null) {
            return REVOLVER_COOLDOWN_FALLBACK;
        }
        return switch (faction) {
            case VIGILANTE -> REVOLVER_COOLDOWN_VIGILANTE;
            case CIVILIAN -> REVOLVER_COOLDOWN_CIVILIAN;
            case KILLER -> REVOLVER_COOLDOWN_KILLER;
            case NEUTRAL -> REVOLVER_COOLDOWN_NEUTRAL;
        };
    }

    /**
     * 根据职业返回左轮应当进入的冷却时长。
     *
     * <p>职业为空时，说明当前玩家可能还未分配身份或调用时机较早，
     * 此时退回到兜底冷却，避免出现空指针或 0 冷却。</p>
     */
    static int getRevolverCooldown(@Nullable Role role) {
        return getRevolverCooldown(role == null ? null : role.getFaction());
    }

    /**
     * 根据阵营获取“被动收入金币上限”。
     *
     * <p>这里把“平民阵营”和“义警阵营”统一视为好人阵营，
     * 共同使用同一套被动收入上限；
     * 若未来扩展职业正确注册了自己的阵营，这里也会自动吃到同样规则。</p>
     */
    static int getPassiveMoneyCap(@Nullable Faction faction) {
        if (faction == null) {
            return PASSIVE_MONEY_CAP_PASSENGER;
        }
        return switch (faction) {
            case KILLER -> PASSIVE_MONEY_CAP_KILLER;
            case NEUTRAL -> PASSIVE_MONEY_CAP_NEUTRAL;
            case CIVILIAN, VIGILANTE -> PASSIVE_MONEY_CAP_PASSENGER;
        };
    }

    /**
     * 计算本次被动收入实际还能加多少金币。
     *
     * <p>规则如下：</p>
     * <p>1. 若本次基础被动收入小于等于 0，则直接不加；</p>
     * <p>2. 若阵营上限配置为负数，则视为“关闭上限”，正常发放全部被动收入；</p>
     * <p>3. 若当前金币已经达到或超过上限，则本次不再加钱；</p>
     * <p>4. 若本次结算会超过上限，则只补足到上限，不会溢出。</p>
     */
    static int getPassiveMoneyAmount(@Nullable Faction faction, int currentBalance, int baseIncome) {
        if (baseIncome <= 0) {
            return 0;
        }

        int cap = getPassiveMoneyCap(faction);
        if (cap < 0) {
            return baseIncome;
        }
        if (currentBalance >= cap) {
            return 0;
        }

        return Math.min(baseIncome, cap - currentBalance);
    }

    /**
     * 直接根据玩家当前职业计算左轮冷却。
     *
     * <p>这是左轮服务端开火逻辑最方便使用的入口。
     * 它会先从 {@link GameWorldComponent} 里取出玩家当前职业，再映射到对应阵营冷却。</p>
     */
    static int getRevolverCooldown(@NotNull PlayerEntity player) {
        Role role = GameWorldComponent.KEY.get(player.getWorld()).getRole(player);
        return getRevolverCooldown(role);
    }

    static int getInTicks(int minutes, int seconds) {
        return (minutes * 60 + seconds) * 20;
    }

    interface DeathReasons {
        Identifier GENERIC = Wathe.id("generic");
        Identifier KNIFE = Wathe.id("knife_stab");
        Identifier GUN = Wathe.id("gun_shot");
        Identifier BAT = Wathe.id("bat_hit");
        Identifier GRENADE = Wathe.id("grenade");
        Identifier POISON = Wathe.id("poison");
        Identifier BED_POISON = Wathe.id("bed_poison");
        Identifier FELL_OUT_OF_TRAIN = Wathe.id("fell_out_of_train");
        Identifier MENTAL_BREAKDOWN = Wathe.id("mental_breakdown");
    }
}
