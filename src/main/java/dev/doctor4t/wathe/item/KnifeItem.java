package dev.doctor4t.wathe.item;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.Wathe;
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
        /*
         * 匕首准心和蓄力提示继续使用 TARGET 语义。
         * 这个方法被扩展客户端 HUD / 旧窄 mixin 复用，所以不要改成真实攻击判定。
         */
        return WeaponTargetingApi.getVisibleAlivePlayerTarget(user, 3F);
    }

    public static @Nullable HitResult getKnifeAttackTarget(PlayerEntity user) {
        return getKnifeAttackTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getKnifeAttackTarget(PlayerEntity user, ItemStack stack) {
        HitResult visibleTarget = getKnifeTarget(user);
        if (visibleTarget instanceof EntityHitResult entityHitResult) {
            /*
             * 匕首也先尊重旧扩展对 getKnifeTarget 的射线修正。
             * 例如播放体这类非玩家实体可以继续挡住发包；玩家实体则必须再经过 ATTACK 语义确认。
             */
            return TargetVisibilityApi.canAttackEntity(user, entityHitResult.getEntity()) ? visibleTarget : null;
        }
        /*
         * visibleTarget 可能是 MISS / 方块命中。
         * 匕首准心可以保持“没有锁到玩家”的表现，但真实松手刺击仍要继续寻找
         * ATTACK 允许、TARGET 隐藏的玩家；实际是否被墙挡住由 WeaponTargetingApi 的攻击射线判断。
         */
        return WeaponTargetingApi.getAttackableAlivePlayerTarget(user, 3F);
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
