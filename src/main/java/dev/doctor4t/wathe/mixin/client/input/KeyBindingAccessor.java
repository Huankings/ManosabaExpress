package dev.doctor4t.wathe.mixin.client.input;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 仅由 ClientInputApi 使用；扩展不得再各自声明 KeyBinding 私有字段 accessor。 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    void wathe$setTimesPressed(int timesPressed);

    @Accessor("boundKey")
    InputUtil.Key wathe$getBoundKey();
}
