package dev.doctor4t.wathe.index;

import com.mojang.serialization.Codec;
import dev.doctor4t.wathe.Wathe;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

public interface WatheDataComponentTypes {
    ComponentType<String> OWNER = register("owner", stringBuilder -> stringBuilder.codec(Codec.STRING).packetCodec(PacketCodecs.STRING));
    ComponentType<String> POISONER = register("poisoner", stringBuilder -> stringBuilder.codec(Codec.STRING).packetCodec(PacketCodecs.STRING));
    /**
     * 托盘附加效果的逻辑类型。
     *
     * <p>原版 wathe 只有 POISONER，一旦扩展模组也拿它来塞“防御试剂 / 幻觉试剂”之类的效果，
     * 就会和真实毒药逻辑完全混在一起。这里额外拆出一个独立的效果类型字段，
     * 让扩展模组能安全地挂载自己的托盘效果，而不会再被原生毒药链路误判。</p>
     */
    ComponentType<String> TRAY_EFFECT = register("tray_effect", stringBuilder -> stringBuilder.codec(Codec.STRING).packetCodec(PacketCodecs.STRING));
    /**
     * 托盘附加效果的施加者。
     *
     * <p>和 TRAY_EFFECT 配套保存。后续回放、护盾抵挡来源、扩展职业统计等逻辑，
     * 都可以通过这个字段回溯到是谁把效果放进托盘里的。</p>
     */
    ComponentType<String> TRAY_EFFECT_OWNER = register("tray_effect_owner", stringBuilder -> stringBuilder.codec(Codec.STRING).packetCodec(PacketCodecs.STRING));
    ComponentType<Boolean> USED = register("used", stringBuilder -> stringBuilder.codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL));

    private static <T> ComponentType<T> register(String name, @NotNull UnaryOperator<ComponentType.Builder<T>> builderOperator) {
        return Registry.register(Registries.DATA_COMPONENT_TYPE, Wathe.id(name), builderOperator.apply(ComponentType.builder()).build());
    }
}
