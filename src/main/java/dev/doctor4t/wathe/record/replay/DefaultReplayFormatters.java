package dev.doctor4t.wathe.record.replay;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.record.GameRecordEvent;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * 主模组默认事件文本格式化器。
 *
 * <p>这里专注做“把事件翻译成句子”，不关心事件是在什么地方采集的。
 * 后续扩展职业模组如果想接入自己的回放事件，只需要复用同一套注册表，
 * 或者在这里仿照本体写自己的 formatter 即可。</p>
 */
public final class DefaultReplayFormatters {
    private DefaultReplayFormatters() {
    }

    private static Map<UUID, ReplayGenerator.PlayerInfo> currentPlayerInfoCache;

    public static void setPlayerInfoCache(Map<UUID, ReplayGenerator.PlayerInfo> cache) {
        currentPlayerInfoCache = cache;
    }

    /**
     * 清空当前这次格式化流程绑定的玩家显示缓存。
     *
     * <p>回放生成现在会按时间线逐条推进玩家职业状态，
     * 因此每轮格式化结束后都应该把上下文缓存释放掉，
     * 避免扩展职业模组后续在别的调用链里误读到上一条事件留下的旧上下文。</p>
     */
    public static void clearPlayerInfoCache() {
        currentPlayerInfoCache = null;
    }

    private static Map<UUID, ReplayGenerator.PlayerInfo> ensurePlayerInfoCache(GameRecordManager.MatchRecord match) {
        if (currentPlayerInfoCache == null) {
            currentPlayerInfoCache = ReplayGenerator.getPlayerInfoCache(match);
        }
        return currentPlayerInfoCache;
    }

    private static @Nullable UUID getActorUuid(GameRecordEvent event) {
        return event.data().containsUuid("actor") ? event.data().getUuid("actor") : null;
    }

    private static @Nullable UUID getTargetUuid(GameRecordEvent event) {
        return event.data().containsUuid("target") ? event.data().getUuid("target") : null;
    }

    private static @Nullable Text actorText(GameRecordEvent event, GameRecordManager.MatchRecord match) {
        UUID actorUuid = getActorUuid(event);
        if (actorUuid == null) {
            return null;
        }
        return ReplayGenerator.formatPlayerName(actorUuid, ensurePlayerInfoCache(match));
    }

    private static @Nullable Text targetText(GameRecordEvent event, GameRecordManager.MatchRecord match) {
        UUID targetUuid = getTargetUuid(event);
        if (targetUuid == null) {
            return null;
        }
        return ReplayGenerator.formatPlayerName(targetUuid, ensurePlayerInfoCache(match));
    }

    @Nullable
    public static Text formatShopPurchase(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }
        Text itemName = ReplayGenerator.formatItemName(event.data(), world);
        return Text.translatable("replay.shop_purchase", actorText, itemName, event.data().getInt("price_paid"));
    }

    @Nullable
    public static Text formatItemPickup(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }
        Text itemName = ReplayGenerator.formatItemName(event.data(), world);
        int count = event.data().getInt("count");
        if (count > 1) {
            return Text.translatable("replay.item_pickup.multiple", actorText, itemName, count);
        }
        return Text.translatable("replay.item_pickup", actorText, itemName);
    }

    @Nullable
    public static Text formatItemUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        /*
         * 先按 tray_effect 分发“下托盘事件”。
         *
         * 这一步是为了让扩展模组可以稳定表达：
         * “给托盘挂上了什么效果”，
         * 而不是只能退化成“使用了什么物品”。
         *
         * 防御试剂、幻觉试剂、定时炸弹都属于这一类。
         */
        Identifier trayEffectId = Identifier.tryParse(event.data().getString("tray_effect"));
        if (trayEffectId != null) {
            ReplayEventFormatter trayEffectFormatter = ReplayRegistry.getTrayEffectPlacementFormatter(trayEffectId);
            if (trayEffectFormatter != null) {
                return trayEffectFormatter.format(event, match, world);
            }
        }

        /*
         * 床效果与托盘效果同理：
         * 这里优先表达“把什么效果塞进了床里”，
         * 只有扩展模组没有注册专用格式化器时，才回退为普通物品使用事件。
         */
        Identifier bedEffectId = Identifier.tryParse(event.data().getString("bed_effect"));
        if (bedEffectId != null) {
            ReplayEventFormatter bedEffectFormatter = ReplayRegistry.getBedEffectPlacementFormatter(bedEffectId);
            if (bedEffectFormatter != null) {
                return bedEffectFormatter.format(event, match, world);
            }
        }

        Identifier itemId = Identifier.tryParse(event.data().getString("item"));
        if (itemId != null) {
            ReplayEventFormatter formatter = ReplayRegistry.getItemUseFormatter(itemId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }
        return null;
    }

    @Nullable
    public static Text formatItemHit(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier itemId = Identifier.tryParse(event.data().getString("item"));
        if (itemId != null) {
            ReplayEventFormatter formatter = ReplayRegistry.getItemHitFormatter(itemId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }

        /*
         * 如果自定义武器没有按“具体物品”注册 formatter，
         * 仍可继续按 hit_type 复用 Wathe 本体的刀 / 枪 / 球棒句式。
         */
        Identifier hitType = Identifier.tryParse(event.data().getString("hit_type"));
        if (hitType == null) {
            return null;
        }
        ReplayEventFormatter fallbackFormatter = ReplayRegistry.getItemHitTypeFormatter(hitType);
        return fallbackFormatter == null ? null : fallbackFormatter.format(event, match, world);
    }

    @Nullable
    public static Text formatPlatterTake(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier itemId = Identifier.tryParse(event.data().getString("item"));
        Identifier trayEffectId = Identifier.tryParse(event.data().getString("tray_effect"));
        if (trayEffectId != null) {
            ReplayEventFormatter trayEffectFormatter = ReplayRegistry.getTrayEffectTakeFormatter(trayEffectId);
            if (trayEffectFormatter != null) {
                return trayEffectFormatter.format(event, match, world);
            }
        }
        if (itemId == null) {
            return null;
        }

        ReplayEventFormatter formatter = ReplayRegistry.getPlatterTakeFormatter(itemId);
        if (formatter != null) {
            return formatter.format(event, match, world);
        }

        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        Text itemName = ReplayGenerator.formatItemName(event.data(), world);
        if (event.data().containsUuid("poisoner")) {
            return Text.translatable("replay.platter_take.poisoned", actorText, itemName);
        }
        return Text.translatable("replay.platter_take", actorText, itemName);
    }

    @Nullable
    public static Text formatConsumeItem(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier trayEffectId = Identifier.tryParse(event.data().getString("tray_effect"));
        if (trayEffectId != null) {
            ReplayEventFormatter trayEffectFormatter = ReplayRegistry.getTrayEffectConsumeFormatter(trayEffectId);
            if (trayEffectFormatter != null) {
                return trayEffectFormatter.format(event, match, world);
            }
        }

        Identifier itemId = Identifier.tryParse(event.data().getString("item"));
        if (itemId != null) {
            ReplayEventFormatter formatter = ReplayRegistry.getConsumeFormatter(itemId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }

        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        Text itemName = ReplayGenerator.formatItemName(event.data(), world);
        boolean poisoned = event.data().getBoolean("poisoned");
        String consumeType = event.data().getString("consume_type");

        String key = switch (consumeType) {
            case "drink_cocktail" -> poisoned ? "replay.consume.drink_cocktail.poisoned" : "replay.consume.drink_cocktail";
            case "drink_potion" -> poisoned ? "replay.consume.drink_potion.poisoned" : "replay.consume.drink_potion";
            default -> poisoned ? "replay.consume.eat.poisoned" : "replay.consume.eat";
        };

        return Text.translatable(key, actorText, itemName);
    }

    @Nullable
    public static Text formatPoisoned(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text victimText = targetText(event, match);
        if (victimText == null) {
            return null;
        }

        String source = event.data().getString("source");
        UUID poisonerUuid = event.data().containsUuid("poisoner_uuid")
                ? event.data().getUuid("poisoner_uuid")
                : getActorUuid(event);
        if ("wathe:bed_poison".equals(source) && poisonerUuid != null) {
            Text poisonerText = ReplayGenerator.formatPlayerName(poisonerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.poisoned.wathe.bed_poison.by", victimText, poisonerText);
        }
        return Text.translatable("replay.poisoned", victimText);
    }

    @Nullable
    public static Text formatDeath(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text victimText = targetText(event, match);
        if (victimText == null) {
            return null;
        }

        String deathReason = event.data().getString("death_reason");
        UUID killerUuid = event.data().containsUuid("replay_actor")
                ? event.data().getUuid("replay_actor")
                : getActorUuid(event);
        UUID poisonerUuid = event.data().containsUuid("poisoner")
                ? event.data().getUuid("poisoner")
                : killerUuid;
        boolean hasPoisonItem = event.data().contains("item") || event.data().contains("item_name");

        if ("wathe:poison".equals(deathReason) && poisonerUuid != null && hasPoisonItem) {
            Text itemName = ReplayGenerator.formatItemName(event.data(), world);
            Text poisonerText = ReplayGenerator.formatPlayerName(poisonerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.death.wathe.poison.from_item", victimText, itemName, poisonerText);
        }

        if ("wathe:poison".equals(deathReason) && poisonerUuid != null) {
            Text poisonerText = ReplayGenerator.formatPlayerName(poisonerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.death.wathe.poison.killed", victimText, poisonerText);
        }

        if ("wathe:bed_poison".equals(deathReason) && poisonerUuid != null) {
            Text poisonerText = ReplayGenerator.formatPlayerName(poisonerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.death.wathe.bed_poison.died", victimText, poisonerText);
        }

        if ("wathe:fell_out_of_train".equals(deathReason) && killerUuid != null) {
            Text killerText = ReplayGenerator.formatPlayerName(killerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.death.wathe.fell_out_of_train.killed", victimText, killerText);
        }

        Identifier deathReasonId = Identifier.tryParse(deathReason);
        if (deathReasonId != null) {
            /*
             * 先让扩展模组按 death_reason 精确接管自己的特殊死亡句式，
             * 再回落到 Wathe 默认的 “killed / died” 模板。
             *
             * 这样不会影响本体现有毒药 / 坠车等特殊处理，
             * 但又能让扩展模组为复杂死因补充更多人物参数。
             */
            ReplayEventFormatter formatter = ReplayRegistry.getDeathReasonFormatter(deathReasonId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }

        if (killerUuid != null) {
            Text killerText = ReplayGenerator.formatPlayerName(killerUuid, ensurePlayerInfoCache(match));
            if ("wathe:knife_stab".equals(deathReason)
                    || "wathe:gun_shot".equals(deathReason)
                    || "wathe:bat_hit".equals(deathReason)
                    || "wathe:grenade".equals(deathReason)) {
                return Text.translatable(
                        buildDeathTranslationKey(deathReason, true),
                        victimText,
                        killerText,
                        ReplayGenerator.resolveItemName(event.data(), world)
                );
            }
            return Text.translatable(buildDeathTranslationKey(deathReason, true), victimText, killerText);
        }
        return Text.translatable(buildDeathTranslationKey(deathReason, false), victimText);
    }

    @Nullable
    public static Text formatShieldBlocked(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier sourceId = Identifier.tryParse(event.data().getString("source"));
        if (sourceId != null) {
            ReplayEventFormatter sourceFormatter = ReplayRegistry.getShieldSourceFormatter(sourceId);
            if (sourceFormatter != null) {
                return sourceFormatter.format(event, match, world);
            }
        }

        Text victimText = targetText(event, match);
        if (victimText == null) {
            return null;
        }

        Text itemName = formatBlockedDamageName(event.data(), world);
        UUID attackerUuid = getActorUuid(event);
        if (attackerUuid != null) {
            Text attackerText = ReplayGenerator.formatPlayerName(attackerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.shield_blocked.wathe.psycho_mode.by_item", victimText, attackerText, itemName);
        }
        return Text.translatable("replay.shield_blocked.wathe.psycho_mode.item", victimText, itemName);
    }

    /**
     * 为“护盾挡伤”事件格式化伤害来源名称。
     *
     * <p>优先展示真正命中的物品；如果这次伤害本来就不是由物品直接造成，
     * 则退回到死亡原因文本，例如“巫毒魔法”。这样像扩展职业的法术、
     * 特殊爆炸之类来源，就不会再统一掉成“未知物品”。</p>
     */
    public static Text formatBlockedDamageName(NbtCompound data, ServerWorld world) {
        if (data.contains("item") || data.contains("item_name")) {
            return ReplayGenerator.formatItemName(data, world);
        }

        String deathReason = data.getString("death_reason");
        if (deathReason != null && !deathReason.isEmpty()) {
            Identifier deathReasonId = Identifier.tryParse(deathReason);
            if (deathReasonId != null) {
                Text deathReasonText = Text.translatableWithFallback(
                        "death_reason." + deathReasonId.toString().replace(':', '.'),
                        prettifyIdentifierPath(deathReasonId.getPath())
                );
                return Text.literal("[")
                        .append(deathReasonText)
                        .append(Text.literal("]"))
                        .formatted(Formatting.WHITE);
            }
        }

        return ReplayGenerator.formatItemName(data, world);
    }

    @Nullable
    public static Text formatSkillUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier skillId = Identifier.tryParse(event.data().getString("skill"));
        if (skillId != null) {
            ReplayEventFormatter formatter = ReplayRegistry.getSkillFormatter(skillId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }

        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        Text targetText = targetText(event, match);
        String key = buildSkillTranslationKey(event.data().getString("skill"), targetText != null);
        return targetText != null ? Text.translatable(key, actorText, targetText) : Text.translatable(key, actorText);
    }

    @Nullable
    public static Text formatGlobalEvent(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Identifier eventId = Identifier.tryParse(event.data().getString("event"));
        if (eventId != null) {
            ReplayEventFormatter formatter = ReplayRegistry.getGlobalEventFormatter(eventId);
            if (formatter != null) {
                return formatter.format(event, match, world);
            }
        }

        Text actorText = actorText(event, match);
        String key = buildGlobalEventTranslationKey(event.data().getString("event"));
        return actorText != null ? Text.translatable(key, actorText) : Text.translatable(key);
    }

    @Nullable
    public static Text formatDoorInteraction(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        return switch (event.data().getString("interaction_type")) {
            case "jam" -> Text.translatable("replay.item_use.wathe.lockpick_jam", actorText);
            case "pry" -> Text.translatable("replay.item_use.wathe.crowbar", actorText);
            default -> null;
        };
    }

    @Nullable
    public static Text formatRoleChanged(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        UUID playerUuid = event.data().containsUuid("player") ? event.data().getUuid("player") : null;
        if (playerUuid == null) {
            return null;
        }

        Text playerText = ReplayGenerator.formatPlayerName(playerUuid, ensurePlayerInfoCache(match));
        MutableText oldRoleText = event.data().contains("old_role")
                ? roleText(Identifier.tryParse(event.data().getString("old_role")))
                : Text.translatable("replay.role.unknown");
        MutableText newRoleText = event.data().contains("new_role")
                ? roleText(Identifier.tryParse(event.data().getString("new_role")))
                : Text.translatable("replay.role.unknown");
        return Text.translatable("replay.role_changed", playerText, oldRoleText, newRoleText);
    }

    @Nullable
    public static Text formatTaskComplete(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        String taskName = event.data().getString("task");
        String taskTranslationKey = taskName == null || taskName.isEmpty()
                ? "replay.task.unknown"
                : "task." + taskName;
        Text taskText = Text.translatable(taskTranslationKey);
        return Text.translatable("replay.task_complete", actorText, taskText);
    }

    @Nullable
    public static Text formatGrenadeUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.grenade", actorText);
    }

    @Nullable
    public static Text formatCrowbarUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.crowbar", actorText);
    }

    @Nullable
    public static Text formatLockpickUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.lockpick_jam", actorText);
    }

    @Nullable
    public static Text formatBodyBagUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        if (actorText == null) {
            return null;
        }

        if (event.data().containsUuid("corpse_owner")) {
            Text corpseOwner = ReplayGenerator.formatPlayerName(event.data().getUuid("corpse_owner"), ensurePlayerInfoCache(match));
            return Text.translatable("replay.item_use.wathe.body_bag", actorText, corpseOwner);
        }
        return Text.translatable("replay.item_use.wathe.body_bag.unknown", actorText);
    }

    @Nullable
    public static Text formatNoteUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.note", actorText);
    }

    @Nullable
    public static Text formatFirecrackerUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.firecracker", actorText);
    }

    @Nullable
    public static Text formatPoisonVialUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.poison_vial", actorText);
    }

    @Nullable
    public static Text formatScorpionUse(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.item_use.wathe.scorpion", actorText);
    }

    @Nullable
    public static Text formatKnifeHit(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        Text targetText = targetText(event, match);
        return actorText == null || targetText == null
                ? null
                : Text.translatable("replay.item_hit.wathe.knife", actorText, ReplayGenerator.resolveItemName(event.data(), world), targetText);
    }

    @Nullable
    public static Text formatGunHit(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        Text targetText = targetText(event, match);
        return actorText == null || targetText == null
                ? null
                : Text.translatable("replay.item_hit.wathe.revolver", actorText, ReplayGenerator.resolveItemName(event.data(), world), targetText);
    }

    @Nullable
    public static Text formatBatHit(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        Text targetText = targetText(event, match);
        return actorText == null || targetText == null
                ? null
                : Text.translatable("replay.item_hit.wathe.bat", actorText, ReplayGenerator.resolveItemName(event.data(), world), targetText);
    }

    @Nullable
    public static Text formatPsychoModeEnd(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.global.wathe.psycho_mode_end", actorText);
    }

    @Nullable
    public static Text formatBlackoutRecovering(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        return Text.translatable("replay.global.wathe.blackout_recovering");
    }

    @Nullable
    public static Text formatBlackoutRestored(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        return Text.translatable("replay.global.wathe.blackout_restored");
    }

    @Nullable
    public static Text formatFishingRodUsed(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        Text actorText = actorText(event, match);
        return actorText == null ? null : Text.translatable("replay.global.wathe.fishing_rod_used", actorText);
    }

    @Nullable
    public static Text formatScorpionSting(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world) {
        NbtCompound data = event.data();
        if (!data.containsUuid("victim")) {
            return null;
        }

        Text victimText = ReplayGenerator.formatPlayerName(data.getUuid("victim"), ensurePlayerInfoCache(match));
        UUID poisonerUuid = data.containsUuid("poisoner")
                ? data.getUuid("poisoner")
                : getActorUuid(event);
        if (poisonerUuid != null) {
            Text poisonerText = ReplayGenerator.formatPlayerName(poisonerUuid, ensurePlayerInfoCache(match));
            return Text.translatable("replay.global.wathe.scorpion_sting", victimText, poisonerText);
        }
        return Text.translatable("replay.global.wathe.scorpion_sting.unknown", victimText);
    }

    private static MutableText roleText(@Nullable Identifier roleId) {
        if (roleId == null) {
            return Text.translatable("replay.role.unknown");
        }

        int roleColor = 0xFFFFFF;
        var role = dev.doctor4t.wathe.api.WatheRoles.getRole(roleId);
        if (role != null) {
            roleColor = role.color();
        }

        String translationKey = "wathe".equals(roleId.getNamespace())
                ? "announcement.title." + roleId.getPath()
                : "announcement.role." + roleId.getNamespace() + "." + roleId.getPath();
        String fallback = prettifyIdentifierPath(roleId.getPath());
        return Text.translatableWithFallback(translationKey, fallback)
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(roleColor)));
    }

    private static String buildDeathTranslationKey(String deathReason, boolean hasKiller) {
        String suffix = hasKiller ? ".killed" : ".died";
        if (deathReason != null && !deathReason.isEmpty()) {
            Identifier id = Identifier.tryParse(deathReason);
            if (id != null) {
                return "replay.death." + id.getNamespace() + "." + id.getPath() + suffix;
            }
        }
        return "replay.death.unknown" + suffix;
    }

    private static String buildSkillTranslationKey(String skillId, boolean hasTarget) {
        String suffix = hasTarget ? ".target" : "";
        if (skillId != null && !skillId.isEmpty()) {
            Identifier id = Identifier.tryParse(skillId);
            if (id != null) {
                return "replay.skill." + id.getNamespace() + "." + id.getPath() + suffix;
            }
        }
        return "replay.skill.unknown" + suffix;
    }

    private static String buildGlobalEventTranslationKey(String eventId) {
        if (eventId != null && !eventId.isEmpty()) {
            Identifier id = Identifier.tryParse(eventId);
            if (id != null) {
                return "replay.global." + id.getNamespace() + "." + id.getPath();
            }
        }
        return "replay.global.unknown";
    }

    private static String prettifyIdentifierPath(String path) {
        String[] parts = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? path : builder.toString();
    }
}
