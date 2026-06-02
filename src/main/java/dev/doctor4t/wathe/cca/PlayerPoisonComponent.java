package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.UUID;

public class PlayerPoisonComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<PlayerPoisonComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("poison"), PlayerPoisonComponent.class);
    public static final Pair<Integer, Integer> clampTime = new Pair<>(800, 1400);
    private final PlayerEntity player;
    public int poisonTicks = -1;
    private int initialPoisonTicks = 0;
    private int poisonPulseCooldown = 0;
    public float pulseProgress = 0f;
    public boolean pulsing = false;
    public UUID poisoner;
    private Identifier poisonSource = GameConstants.DeathReasons.POISON;
    private @Nullable NbtCompound poisonData = null;

    public PlayerPoisonComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        KEY.sync(this.player);
    }

    public void reset() {
        this.poisonTicks = -1;
        this.poisonPulseCooldown = 0;
        this.initialPoisonTicks = 0;
        this.pulseProgress = 0f;
        this.pulsing = false;
        this.poisoner = null;
        this.poisonSource = GameConstants.DeathReasons.POISON;
        this.poisonData = null;
        this.sync();
    }

    @Override
    public void clientTick() {
        if (this.poisonTicks > -1) this.poisonTicks--;
        if (this.poisonTicks > 0) {
            int ticksSinceStart = this.initialPoisonTicks - this.poisonTicks;

            if (ticksSinceStart < 200) return;

            int minCooldown = 10;
            int maxCooldown = 60;
            int dynamicCooldown = minCooldown + (int) ((maxCooldown - minCooldown) * ((float) this.poisonTicks / clampTime.getRight()));

            if (this.poisonPulseCooldown <= 0) {
                this.poisonPulseCooldown = dynamicCooldown;

                this.pulsing = true;

                float minVolume = 0.5f;
                float maxVolume = 1f;
                float volume = minVolume + (maxVolume - minVolume) * (1f - ((float) this.poisonTicks / clampTime.getRight()));

                this.player.playSoundToPlayer(
                        SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                        SoundCategory.PLAYERS,
                        volume,
                        1f
                );
            } else {
                this.poisonPulseCooldown--;
            }
        } else {
            this.poisonPulseCooldown = 0;
        }
    }

    @Override
    public void serverTick() {
        if (this.poisonTicks > -1) {
            this.poisonTicks--;
            if (this.poisonTicks == 0) {
                this.poisonTicks = -1;
                /*
                 * 毒发身亡时要把原始 poisonData 一起带进死亡回放。
                 *
                 * 这样餐盘毒药可以继续显示“因带有毒药的 某食物/饮品 而死”，
                 * 扩展模组后续如果给真实中毒补充了自己的 item / item_name，
                 * 也能顺着同一条链路被 death replay 正确读取出来。
                 */
                GameFunctions.killPlayer(
                        this.player,
                        true,
                        this.poisoner == null ? null : this.player.getWorld().getPlayerByUuid(this.poisoner),
                        this.poisonSource,
                        this.poisonData
                );
                this.poisoner = null;
                this.poisonSource = GameConstants.DeathReasons.POISON;
                this.poisonData = null;
                this.sync();
            }
        }
    }

    public void setPoisonTicks(int ticks, UUID poisoner) {
        this.setDetailedPoisonTicks(ticks, poisoner, GameConstants.DeathReasons.POISON, null);
    }

    /**
     * 为回放系统保留扩展型中毒入口。
     *
     * <p>source 用于区分“普通毒药 / 蝎子 / 扩展模组的其它毒源”；extra 则允许扩展模组
     * 继续塞入具体物品名、托盘附加状态等数据，后续死亡回放就能还原成更完整的句子。</p>
     */
    public void setDetailedPoisonTicks(int ticks, UUID poisoner, Identifier source, @Nullable NbtCompound extra) {
        this.poisoner = poisoner;
        this.poisonSource = source == null ? GameConstants.DeathReasons.POISON : source;
        this.poisonData = extra == null ? null : extra.copy();
        this.poisonTicks = ticks;
        if (this.initialPoisonTicks == 0) this.initialPoisonTicks = ticks;

        if (this.player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            GameRecordManager.recordPoisoned(serverPlayer, poisoner, ticks, this.poisonSource, this.poisonData);
        }

        this.sync();
    }

    public Identifier getPoisonSource() {
        return this.poisonSource;
    }

    public @Nullable UUID getPoisoner() {
        return this.poisoner;
    }

    public @Nullable NbtCompound getPoisonData() {
        return this.poisonData == null ? null : this.poisonData.copy();
    }

    public int getInitialPoisonTicks() {
        return this.initialPoisonTicks;
    }

    public int getPoisonPulseCooldown() {
        return this.poisonPulseCooldown;
    }

    /**
     * 真毒在“心跳阶段”时才需要让客户端开始抖动。
     *
     * <p>这里单独封一个判断，方便后续其它客户端表现（比如相机、HUD、特效）
     * 统一复用同一段判断逻辑，而不是到处重复写“前 200 tick 不处理”。</p>
     */
    public boolean isHeartbeatPhase() {
        return this.poisonTicks > 0 && this.initialPoisonTicks > 0 && this.initialPoisonTicks - this.poisonTicks >= 200;
    }

    /**
     * 这个进度专门给客户端抖动使用：进入心跳阶段后从 0 逐步增长到 1。
     *
     * <p>这样相机抖动的幅度就能随着中毒时间自然增强，既不会一开始就太猛，
     * 也不会在整段毒效中始终保持同一强度。</p>
     */
    public float getHeartbeatProgress() {
        if (!this.isHeartbeatPhase()) return 0f;

        int heartbeatTicks = Math.max(0, this.initialPoisonTicks - this.poisonTicks - 200);
        int heartbeatLength = Math.max(1, this.initialPoisonTicks - 200);
        return MathHelper.clamp(heartbeatTicks / (float) heartbeatLength, 0f, 1f);
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        if (this.poisoner != null) tag.putUuid("poisoner", this.poisoner);
        tag.putInt("poisonTicks", this.poisonTicks);
        tag.putInt("initialPoisonTicks", this.initialPoisonTicks);
        tag.putString("poisonSource", this.poisonSource.toString());
        if (this.poisonData != null) {
            tag.put("poisonData", this.poisonData.copy());
        }
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.poisoner = tag.contains("poisoner") ? tag.getUuid("poisoner") : null;
        this.poisonTicks = tag.contains("poisonTicks") ? tag.getInt("poisonTicks") : -1;
        this.initialPoisonTicks = tag.contains("initialPoisonTicks") ? tag.getInt("initialPoisonTicks") : 0;
        this.poisonSource = tag.contains("poisonSource") ? Identifier.of(tag.getString("poisonSource")) : GameConstants.DeathReasons.POISON;
        this.poisonData = tag.contains("poisonData") ? tag.getCompound("poisonData").copy() : null;
    }
}
