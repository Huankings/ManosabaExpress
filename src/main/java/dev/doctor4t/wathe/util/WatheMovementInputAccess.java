package dev.doctor4t.wathe.util;

/**
 * LivingEntityMixin 暴露给玩家体力逻辑使用的内部输入读取桥。
 * sidewaysSpeed / forwardSpeed 字段实际定义在 LivingEntity 上，不能直接 shadow 到 PlayerEntityMixin，
 * 否则服务端运行期 Mixin 会在 PlayerEntity 目标类里找字段并导致启动崩溃。
 */
public interface WatheMovementInputAccess {
    boolean wathe$isTryingHorizontalSelfMove();
}
