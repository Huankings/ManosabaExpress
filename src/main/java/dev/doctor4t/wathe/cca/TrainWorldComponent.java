package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.game.mapeffect.HarpyExpressTrainMapEffect;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

public class TrainWorldComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<TrainWorldComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("train"), TrainWorldComponent.class);

    private final World world;
    private int speed = 0; // im km/h
    private int time = 0;
    private boolean snow = false;
    private boolean fog = false;
    private boolean hud = false;
    private TimeOfDay timeOfDay = TimeOfDay.DAY;

    public TrainWorldComponent(World world) {
        this.world = world;
    }

    private void sync() {
        TrainWorldComponent.KEY.sync(this.world);
    }

    public void setSpeed(int speed) {
        this.speed = speed;
        this.sync();
    }

    public int getSpeed() {
        return speed;
    }

    public float getTime() {
        return time;
    }

    public void setTime(int time) {
        this.time = time;
        this.sync();
    }

    public boolean isSnowing() {
        return WatheClient.gameComponent.getMapEffect() instanceof HarpyExpressTrainMapEffect && snow;
    }

    public void setSnow(boolean snow) {
        this.snow = snow;
        this.sync();
    }

    public boolean isFoggy() {
        return fog;
    }

    public void setFog(boolean fog) {
        this.fog = fog;
        this.sync();
    }

    public boolean hasHud() {
        return hud;
    }

    public void setHud(boolean hud) {
        this.hud = hud;
        this.sync();
    }

    public TimeOfDay getTimeOfDay() {
        return timeOfDay;
    }

    public void setTimeOfDay(TimeOfDay timeOfDay) {
        this.timeOfDay = timeOfDay;
        this.applyTimeOfDayToWorld(true);
        this.sync();
    }

    public static void setServerTimeOfDay(MinecraftServer server, TimeOfDay timeOfDay) {
        /*
         * 原版 /time set 会把所有已加载世界一起改掉。
         * 多维度地图里如果只改当前维度，其他维度旧的 TrainWorldComponent
         * 仍可能在后续 tick 把客户端看到的时间抢回去，所以 Wathe 的视觉时间
         * 也按原版指令习惯同步到整台服务器。
         */
        for (ServerWorld serverWorld : server.getWorlds()) {
            TrainWorldComponent component = KEY.get(serverWorld);
            component.timeOfDay = timeOfDay;
            component.applyTimeOfDayToWorld(true);
            component.sync();
        }
    }

    private void applyTimeOfDayToWorld(boolean syncPlayersImmediately) {
        if (this.world instanceof ServerWorld serverWorld) {
            /*
             * 多维度地图不能只改 TrainWorldComponent 的枚举值。
             * 客户端天空、太阳/月亮位置和夜晚雾色读取的是 Minecraft 世界时间，
             * 所以这里在设置视觉时间时同步写入当前维度本身的 timeOfDay。
             */
            serverWorld.setTimeOfDay(this.timeOfDay.time);

            if (syncPlayersImmediately) {
                boolean doDaylightCycle = serverWorld.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE);
                WorldTimeUpdateS2CPacket packet = new WorldTimeUpdateS2CPacket(serverWorld.getTime(), this.timeOfDay.time, doDaylightCycle);
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    player.networkHandler.sendPacket(packet);
                }
            }
        }
    }

    @Override
    public void readFromNbt(NbtCompound nbtCompound, RegistryWrapper.WrapperLookup wrapperLookup) {
        this.setSpeed(nbtCompound.getInt("Speed"));
        this.setTime(nbtCompound.getInt("Time"));
        this.setSnow(nbtCompound.getBoolean("Snow"));
        this.setFog(nbtCompound.getBoolean("Fog"));
        this.setHud(nbtCompound.getBoolean("Hud"));
        this.setTimeOfDay(TimeOfDay.valueOf(nbtCompound.getString("TimeOfDay")));
    }

    @Override
    public void writeToNbt(NbtCompound nbtCompound, RegistryWrapper.WrapperLookup wrapperLookup) {
        nbtCompound.putInt("Speed", speed);
        nbtCompound.putInt("Time", time);
        nbtCompound.putBoolean("Snow", snow);
        nbtCompound.putBoolean("Fog", fog);
        nbtCompound.putBoolean("Hud", hud);
        nbtCompound.putString("TimeOfDay", timeOfDay.name());
    }

    @Override
    public void clientTick() {
        tickTime();
    }

    private void tickTime() {
        if (speed > 0) {
            time++;
        } else {
            time = 0;
        }
    }

    @Override
    public void serverTick() {
        tickTime();

        ServerWorld serverWorld = (ServerWorld) world;
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(serverWorld);
        if (gameComponent.isRunning() && gameComponent.getMapEffect() instanceof HarpyExpressTrainMapEffect) {
            /*
             * 多维度存档里可能有多个世界保留了旧的列车地图效果。
             * 如果 INACTIVE 世界也持续写时间，它会把当前游戏维度刚设置好的
             * NIGHT / SUNDOWN 抢回 DAY，表现成黑夜一闪而过。
             */
            applyTimeOfDayToWorld(false);
        }
    }

    public enum TimeOfDay implements StringIdentifiable {
        DAY(6000),
        NIGHT(18000),
        SUNDOWN(12800);

        final int time;

        TimeOfDay(int time) {
            this.time = time;
        }

        public int getTimeValue() {
            return this.time;
        }

        @Override
        public String asString() {
            return this.name();
        }
    }

}
