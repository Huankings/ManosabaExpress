package dev.doctor4t.wathe.item;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.combat.KnifeAttackApi;
import dev.doctor4t.wathe.api.combat.KnifeTargetContext;
import dev.doctor4t.wathe.api.combat.WeaponTargetingApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.index.WatheCosmetics;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.util.KnifeStabPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.StackReference;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Random;

public class KnifeItem extends Item implements ItemWithSkin {

    /**
     * the registry ID of the knife item
     */
    public static final Identifier ITEM_ID = Wathe.id("knife");

    public KnifeItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, @NotNull PlayerEntity user, Hand hand) {
        ItemStack itemStack = user.getStackInHand(hand);
        user.setCurrentHand(hand);
        user.playSound(WatheSounds.ITEM_KNIFE_PREPARE, 1.0f, 1.0f);
        return TypedActionResult.consume(itemStack);
    }

    @Override
    public boolean onClicked(ItemStack stack, ItemStack otherStack, Slot slot, ClickType clickType, PlayerEntity player, StackReference cursorStackReference) {
        if (clickType == ClickType.RIGHT && otherStack.isEmpty())  {
            if (Wathe.isSupporter(player)) {
                Skin currentSkin = Skin.fromString(WatheCosmetics.getSkin(stack));
                WatheCosmetics.setSkin(player, stack, Skin.getNext(currentSkin).getName());
            }

            return true;
        } else return false;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        Skin skin = Skin.fromString(WatheCosmetics.getSkin(stack));

        if (skin != null) {
            tooltip.add(Text.translatable("tip.skin").styled(style -> style.withColor(Colors.GRAY))
                    .append(Text.literal(TextUtils.formatValueString(skin.tooltipName)).styled(style -> style.withColor(skin.getColor())))
                    .append(Text.translatable("tip.change_skin").styled(style -> style.withColor(Colors.GRAY))));
        }

        super.appendTooltip(stack, context, tooltip, type);
    }


    @Override
    public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (user.isSpectator()) {
            return;
        }

        if (remainingUseTicks >= this.getMaxUseTime(stack, user) - 10 || !(user instanceof PlayerEntity attacker) || !world.isClient)
            return;
        HitResult collision = getKnifeAttackTarget(attacker, stack);
        if (collision instanceof EntityHitResult entityHitResult) {
            Entity target = entityHitResult.getEntity();
            ClientPlayNetworking.send(new KnifeStabPayload(target.getId()));
        }
    }

    public static @Nullable HitResult getKnifeTarget(PlayerEntity user) {
        return getKnifeTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getKnifeTarget(PlayerEntity user, ItemStack stack) {
        /*
         * 匕首准心和蓄力提示继续使用 TARGET 语义，然后交给公开目标规则补充播放体、
         * 职业召唤物等扩展实体。这里的结果只影响视觉与发包目标，不代表服务端一定接受命中。
         */
        HitResult defaultTarget = WeaponTargetingApi.getVisibleAlivePlayerTarget(user, 3F);
        HitResult resolvedTarget = KnifeAttackApi.resolveTarget(new KnifeTargetContext(
                user,
                stack,
                3.0F,
                KnifeTargetContext.Purpose.VISIBLE,
                defaultTarget
        ));
        if (resolvedTarget instanceof EntityHitResult entityHitResult
                && !TargetVisibilityApi.canTargetEntity(user, entityHitResult.getEntity())) {
            return null;
        }
        return resolvedTarget;
    }

    public static @Nullable HitResult getKnifeAttackTarget(PlayerEntity user) {
        return getKnifeAttackTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getKnifeAttackTarget(PlayerEntity user, ItemStack stack) {
        /*
         * 真实刺击必须从头使用 ATTACK 语义解析，不能先依赖准心结果。
         * 这样只隐藏 TARGET 的尸体伪装不会获得伤害免疫，而扩展实体仍可通过 KnifeAttackApi
         * 为 ATTACK 阶段提供自己的命中结果。最终再统一检查 TargetVisibilityApi。
         */
        HitResult defaultTarget = WeaponTargetingApi.getAttackableAlivePlayerTarget(user, 3F);
        HitResult resolvedTarget = KnifeAttackApi.resolveTarget(new KnifeTargetContext(
                user,
                stack,
                3.0F,
                KnifeTargetContext.Purpose.ATTACK,
                defaultTarget
        ));
        if (resolvedTarget instanceof EntityHitResult entityHitResult
                && !TargetVisibilityApi.canAttackEntity(user, entityHitResult.getEntity())) {
            return null;
        }
        return resolvedTarget;
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.SPEAR;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return 100;
    }

    public enum Skin {
        DEFAULT(Colors.LIGHT_GRAY, "Kitchen Knife"),
        CEREMONIAL(0xFFD98C28, "Ceremonial Dagger"),
        PICK(0xFF8D4A51, "Ice Pick");

        public final int color;
        public final @Nullable String tooltipName;
        public final Random random;

        Skin(int color, @Nullable String tooltipName) {
            this.color = color;
            this.tooltipName = tooltipName;
            this.random = new Random();
        }

        public String getName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public int getColor() {
            return this.color;
        }

        public static Skin fromString(String name) {
            for (Skin skin : Skin.values()) if (skin.getName().equalsIgnoreCase(name)) return skin;
            return DEFAULT;
        }

        public static Skin getNext(Skin skin) {
            Skin[] values = Skin.values();
            return values[(skin.ordinal() + 1) % values.length];
        }
    }
}
