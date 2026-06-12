package dev.doctor4t.wathe.datagen;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.index.WatheBlocks;
import dev.doctor4t.wathe.index.WatheEntities;
import dev.doctor4t.wathe.index.WatheItems;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.registry.RegistryWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class WatheLangGen extends FabricLanguageProvider {

    public WatheLangGen(FabricDataOutput dataOutput, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        super(dataOutput, registryLookup);
    }

    @Override
    public void generateTranslations(RegistryWrapper.WrapperLookup wrapperLookup, @NotNull TranslationBuilder builder) {
        WatheBlocks.registrar.generateLang(wrapperLookup, builder);
        WatheItems.registrar.generateLang(wrapperLookup, builder);
        WatheEntities.registrar.generateLang(wrapperLookup, builder);

//        builder.add(WatheItems.LETTER.getTranslationKey() + ".instructions", "Instructions");
//        builder.add("tip.letter.killer.tooltip1", "Thank you for taking this job. Please eliminate the following targets:");
//        builder.add("tip.letter.killer.tooltip.target", "- %s");
//        builder.add("tip.letter.killer.tooltip2", "Please do so with the utmost discretion and do not get caught. Good luck.");
//        builder.add("tip.letter.killer.tooltip3", "");
//        builder.add("tip.letter.killer.tooltip4", "P.S.: Don't forget to use your instinct [Left Alt] and use the train's exterior to relocate.");
//
//        builder.add(WatheItems.LETTER.getTranslationKey() + ".notes", "Notes");
//        builder.add("tip.letter.detective.tooltip1", "Multiple homicides, several wealthy victims.");
//        builder.add("tip.letter.detective.tooltip2", "Have to be linked... Serial killer? Assassin? Killer?");
//        builder.add("tip.letter.detective.tooltip3", "Potential next victims frequent travelers of the Harpy Express.");
//        builder.add("tip.letter.detective.tooltip4", "Perfect situation to corner but need to keep targets safe.");

        builder.add("lobby.players.count", "Players boarded: %s / %s");
        builder.add("lobby.autostart.active", "Game will start once 6+ players are boarded");
        builder.add("lobby.autostart.time", "Game starting in %ss");
        builder.add("lobby.autostart.starting", "Game starting");
        builder.add("lobby.voting.active", "Map voting in progress");
        builder.add("lobby.voting.keybind_hint", "Press [%s] to open / [ESC] to close map voting");

        builder.add("announcement.role.civilian", "Civilian!");
        builder.add("announcement.role.vigilante", "Vigilante!");
        builder.add("announcement.role.killer", "Killer!");
        builder.add("announcement.role.loose_end", "Loose End!");
        builder.add("announcement.title.civilian", "Civilians");
        builder.add("announcement.title.vigilante", "Vigilantes");
        builder.add("announcement.title.killer", "Killers");
        builder.add("announcement.title.loose_end", "Loose Ends");

        builder.add("announcement.welcome", "Welcome aboard %s");
        builder.add("announcement.premise", "There is a killer aboard the train.");
        builder.add("announcement.premises", "There are %s killers aboard the train.");
        builder.add("announcement.goal.civilian", "Stay safe and survive till the end of the ride.");
        builder.add("announcement.goal.vigilante", "Eliminate any murderers and protect the civilians.");
        builder.add("announcement.goal.killer", "Eliminate a passenger to succeed, before time runs out.");
        builder.add("announcement.goals.civilian", "Stay safe and survive till the end of the ride.");
        builder.add("announcement.goals.vigilante", "Eliminate any murderers and protect the civilians.");
        builder.add("announcement.goals.killer", "Eliminate all civilians before time runs out.");
        builder.add("announcement.win.civilian", "Passengers Win!");
        builder.add("announcement.win.vigilante", "Passengers Win!");
        builder.add("announcement.win.killer", "Killers Win!");
        builder.add("announcement.win.loose_end", "%s Wins!");
        builder.add("announcement.loose_ends.welcome", "Welcome aboard... Loose End.");
        builder.add("announcement.loose_ends.premise", "Everybody on the train has a derringer and a knife.");
        builder.add("announcement.loose_ends.goal", "Tie all loose ends before they tie you. Good luck.");
        builder.add("announcement.loose_ends.winner", "%s Wins!");

        builder.add("tip.letter.name", "Dear %s, welcome aboard the Harpy Express!");
        builder.add("tip.letter.room", "Please find attached your ticket as well as the key for accessing");
        builder.add("tip.letter.room.grand_suite", "the Grand Suite");
        builder.add("tip.letter.room.cabin_suite", "your Cabin Suite");
        builder.add("tip.letter.room.twin_cabin", "your Twin Cabin");
        builder.add("tip.letter.tooltip1", "%s for your trip on the 1st of January 1923.");
        builder.add("tip.letter.tooltip2", "La Sirène wishes you a pleasant and safe voyage.");
        builder.add("tip.skin", "Skin: ");
        builder.add("tip.change_skin", " [Right-click to change (supporter only)]");

        builder.add("itemGroup.wathe.building", "Wathe: Building Blocks");
        builder.add("itemGroup.wathe.decoration", "Wathe: Decoration & Functional");
        builder.add("itemGroup.wathe.equipment", "Wathe: Equipment");

        builder.add("container.cargo_box", "Cargo Box");
        builder.add("container.cabinet", "Cabinet");
        builder.add("subtitles.block.cargo_box.close", "Cargo Box closes");
        builder.add("subtitles.block.cargo_box.open", "Cargo Box opens");
        builder.add("subtitles.block.door.toggle", "Door operates");
        builder.add("subtitles.item.crowbar.pry", "Crowbar pries door");

        builder.add("tip.door.locked", "This door is locked and cannot be opened.");
        builder.add("tip.door.requires_key", "This door is locked and requires a key to be opened.");
        builder.add("tip.door.requires_different_key", "This door is locked and requires a different key to be opened.");
        builder.add("tip.door.jammed", "This door is jammed and cannot be opened at the moment!");
        builder.add("tip.derringer.used", "Used: cannot be shot anymore, get a kill for another chance!");
        builder.add("tip.grenade.switch_throw_mode", "Current grenade throw mode switched to: %s");
        builder.add("tip.grenade.current_throw_mode", "Current grenade throw mode: %s");
        builder.add("tip.grenade.throw_mode.direct", "Direct Throw");
        builder.add("tip.grenade.throw_mode.charged", "Charged");

        builder.add("tip.cooldown", "On cooldown: %s");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.KNIFE) + ".tooltip", "Right-click, hold for a second and get close to your victim\nAfter a kill, cannot be used for 1 minute\nAttack to knock back / push a player (no cooldown)");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.REVOLVER) + ".tooltip", "Point, right-click and shoot\nDrops if you kill an innocent");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.DERRINGER) + ".tooltip", "Point, right-click and shoot\nCan only be shot once, so make it count!\nShot is replenished after a kill");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.GRENADE) + ".tooltip", "Left-click to switch between Direct Throw and Charged modes\nDirect Throw releases instantly at base speed, Charged mode holds right-click for up to 1 second\nSingle use, 5 minute cooldown");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.PSYCHO_MODE) + ".tooltip", "\"Do you like hurting other people?\"\nHides your identity and allows you to go crazy with a bat for 30 seconds\nBat kills on full swing and cannot be unselected for the duration of the ability\nActivated instantly upon purchase, 5 minute cooldown");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.POISON_VIAL) + ".tooltip", "Slip in food or drinks to poison the next pickup");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.FIRECRACKER) + ".tooltip", "Detonates 15 seconds after being placed on ground\nGood to simulate gunshots and lure people");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.SCORPION) + ".tooltip", "Slip in a bed to poison the next person looking for a rest");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.LOCKPICK) + ".tooltip", "Use on any locked door to open it (no cooldown)\nSneak-use on a door to jam it for 1 minute (3 minute cooldown)");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.CROWBAR) + ".tooltip", "Use on any door to open it permanently");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.BODY_BAG) + ".tooltip", "Use on a dead body to bag it up and remove it\nSingle use, 5 minute cooldown");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.BLACKOUT) + ".tooltip", "Turn off all lights aboard for 15 to 20 seconds\nUse your instinct [left-alt] to see your targets in the dark\nActivated instantly on purchase, 5 minute cooldown");
        builder.add(TextUtils.getItemTranslationKey(WatheItems.NOTE) + ".tooltip", "Write a message and pin it for others to see\nSneak-use to write a message, then use on a wall or floor to place\nInvisible in hand");

        builder.add("game.win.killers", "The killers reached their kill count, they win!");
        builder.add("game.win.passengers", "All killers were eliminated: the passengers win!");
        builder.add("game.win.time", "The killers ran out of time: the passengers win!");
        builder.add("game.win.loose_end", "They tied all of their loose ends!");

        builder.add("key.wathe.instinct", "Instinct");
        builder.add("key.wathe.task_points", "Task Point Overlay");
        builder.add("category.wathe.keybinds", "Wathe");

        builder.add("task.feel", "You feel like ");
        builder.add("task.fake", "You could fake ");
        builder.add("task.sleep", "getting some sleep.");
        builder.add("task.outside", "getting some fresh air.");
        builder.add("task.water", "soaking in water.");
        builder.add("task.fire", "warming up by a fire.");
        builder.add("task.shift", "crouching for a bit.");
        builder.add("task.stare", "staring at someone for a while.");
        builder.add("task.away", "staying away from other people.");
        builder.add("task.run", "going for a run.");
        builder.add("task.sit", "sitting down for a bit.");
        builder.add("task.cook", "cooking some raw food.");
        builder.add("task.potion", "drinking a potion.");
        builder.add("task.music", "playing with a note block.");
        builder.add("task.book", "reading on a lectern.");
        builder.add("task.stay", "staying still.");
        builder.add("task.fish", "going fishing.");
        builder.add("hud.mood.breakdown_warning", "Mental breakdown imminent");
        builder.add("hud.task_point.toggle.enabled", "Task point overlay enabled");
        builder.add("hud.task_point.toggle.disabled", "Task point overlay disabled");
        builder.add("hud.task_point.bed", "Bed");
        builder.add("hud.task_point.keyed_door", "Matching Door");
        builder.add("hud.task_point.water_source", "Water");
        builder.add("hud.task_point.fire_source", "Fire Source");
        builder.add("hud.task_point.food_tray", "Food Tray");
        builder.add("hud.task_point.cocktail_tray", "Cocktail Tray");
        builder.add("hud.task_point.seat", "Seat");
        builder.add("hud.task_point.potion_tray", "Potion Tray");
        builder.add("hud.task_point.note_block", "Note Block");
        builder.add("hud.task_point.lectern", "Lectern");
        builder.add("hud.task_point.fishing_rod_tray", "Fishing Rod Tray");
        builder.add("hud.task_point.furnace", "Furnace");
        builder.add("hud.task_point.smoker", "Smoker");
        builder.add("hud.task_point.raw_food_tray", "Raw Food Tray");
        builder.add("hud.task_point.fuel_tray", "Fuel Tray");
        builder.add("task.drink", "getting a drink.");
        builder.add("task.eat", "getting a snack.");
        builder.add("game.player.stung", "You feel something stinging you in your sleep.");
        builder.add("game.psycho_mode.time", "Psycho Mode: %s");
        builder.add("game.psycho_mode.text", "Kill them all!");
        builder.add("game.psycho_mode.over", "Psycho Mode Over!");
        builder.add("game.tip.cohort", "Killer Cohort");
        builder.add("game.start_error.not_enough_players", "Game cannot start: %s players minimum are required.");
        builder.add("game.start_error.game_running", "Game cannot start: a game is already running. Please try again from the lobby.");

        builder.add("replay.title", "Match Replay");
        builder.add("replay.footer", "Replay complete.");
        builder.add("replay.role.unknown", "Unknown Role");
        builder.add("replay.item.unknown", "Unknown Item");
        builder.add("replay.shop_purchase", "%s bought %s for %s coins");
        builder.add("replay.item_pickup", "%s picked up %s");
        builder.add("replay.item_pickup.multiple", "%s picked up %s x%s");
        builder.add("replay.platter_take", "%s took %s from the platter");
        builder.add("replay.platter_take.poisoned", "%s took poisoned %s from the platter");
        builder.add("replay.consume.eat", "%s ate %s");
        builder.add("replay.consume.eat.poisoned", "%s ate poisoned %s");
        builder.add("replay.consume.drink_cocktail", "%s drank %s");
        builder.add("replay.consume.drink_cocktail.poisoned", "%s drank poisoned %s");
        builder.add("replay.consume.drink_potion", "%s drank %s");
        builder.add("replay.consume.drink_potion.poisoned", "%s drank poisoned %s");
        builder.add("replay.poisoned", "%s started being poisoned");
        builder.add("replay.poisoned.wathe.bed_poison.by", "%s was stung by a scorpion placed by %s");
        builder.add("replay.death.wathe.knife_stab.killed", "%s was killed by %s's %s");
        builder.add("replay.death.wathe.gun_shot.killed", "%s was killed by %s's %s");
        builder.add("replay.death.wathe.bat_hit.killed", "%s was killed by %s's %s");
        builder.add("replay.death.wathe.grenade.killed", "%s was blown up by %s's thrown %s");
        builder.add("replay.death.wathe.poison.from_item", "%s died from poisoned %s, the poison came from %s");
        builder.add("replay.death.wathe.poison.killed", "%s died from poison caused by %s");
        builder.add("replay.death.wathe.poison.died", "%s died from poison");
        builder.add("replay.death.wathe.bed_poison.died", "%s died from a scorpion sting placed by %s");
        builder.add("replay.death.wathe.mental_breakdown.died", "%s died of mental breakdown");
        builder.add("replay.death.wathe.fell_out_of_train.died", "%s fell out of the world");
        builder.add("replay.death.wathe.fell_out_of_train.killed", "%s was pushed out of the world by %s");
        builder.add("replay.death.unknown.died", "%s died");
        builder.add("replay.death.unknown.killed", "%s was killed by %s");
        builder.add("replay.shield_blocked.wathe.psycho_mode.by_item", "%s's psycho shield blocked damage from %s's %s");
        builder.add("replay.shield_blocked.wathe.psycho_mode.item", "%s's psycho shield blocked damage from %s");
        builder.add("replay.role_changed", "%s changed identity from %s to %s");
        builder.add("replay.task_complete", "%s completed the task: %s");
        builder.add("replay.task.unknown", "Unknown Task");
        builder.add("replay.item_use.wathe.grenade", "%s threw a grenade");
        builder.add("replay.item_use.wathe.crowbar", "%s pried open a door with a crowbar");
        builder.add("replay.item_use.wathe.lockpick_jam", "%s jammed a door with a lockpick");
        builder.add("replay.item_use.wathe.body_bag", "%s used a body bag to remove %s's corpse");
        builder.add("replay.item_use.wathe.body_bag.unknown", "%s used a body bag to remove a corpse");
        builder.add("replay.item_use.wathe.note", "%s placed a note");
        builder.add("replay.item_use.wathe.firecracker", "%s placed firecrackers");
        builder.add("replay.item_use.wathe.poison_vial", "%s slipped poison into a platter");
        builder.add("replay.item_use.wathe.scorpion", "%s placed a scorpion into a bed");
        builder.add("replay.item_hit.wathe.knife", "%s used %s to hit %s");
        builder.add("replay.item_hit.wathe.revolver", "%s used %s to hit %s");
        builder.add("replay.item_hit.wathe.bat", "%s used %s to hit %s");
        builder.add("replay.global.wathe.psycho_mode_end", "%s's psycho mode ended");
        builder.add("replay.global.wathe.blackout_recovering", "Power is returning");
        builder.add("replay.global.wathe.blackout_restored", "Power has fully returned");
        builder.add("replay.global.wathe.fishing_rod_used", "%s used a fishing rod");
        builder.add("replay.global.wathe.scorpion_sting", "%s was stung by a scorpion placed by %s");
        builder.add("replay.global.wathe.scorpion_sting.unknown", "%s was stung by a scorpion");

        builder.add("wathe.gui.reset", "Clear");

        builder.add("wathe.map_variables.help", """
                    gameModeAndMapEffect: The default game mode and map effect the map will use (with auto-start or when using the train horn).
                    spawnPos: The spawn position and orientation players will be reset to once the game ends.
                    spectatorSpawnPos: The spawn position and orientation players will be set to when set as spectators at the start of a game.
                    readyArea: The lobby area which players need to be in to be selected for a game.
                    playAreaOffset: The offset players will be teleported by from the ready area into the play area.
                    playArea: The play area outside which players will be eliminated.
                    resetTemplateArea: The template that will be copied over the play area in order to reset the map.
                    resetPasteOffset: The offset at which the template should be pasted.
                """);
        builder.add("wathe.map_variables.set", "Map variable %s successfully set to %s");

        builder.add("wathe.game_settings.help", """
                    weights: The role weight system that keeps track of the roles players got and favorizes players who did not get vigilante / killer yet to get those roles. Disable if you wish for full-random.
                    autoStart: Time before the game starts automatically when enough players are boarded in seconds.
                    backfire: When a civilian shoots an innocent, chance said civilian will shoot themselves instead. 0 (default) is 0%, 1 is 100%.
                    roleDividend: The amount of players for which 1 of the role will be chosen (default: 5 for both roles). E.g: 4 -> 1 in 4 players will be that role, meaning 2 of that role for 8 people, 3 for 12, etc...
                    bounds: Enable or disable game bounds that limit spectators to the play area.
                """);

        builder.add("commands.supporter_only", "Super silly supporter commands are reserved for Patreon and YouTube members; if you wanna try them out, please consider supporting! <3");

        builder.add("wathe.midnightconfig.title", "The Last Voyage of the Harpy Express - Config");
        builder.add("wathe.midnightconfig.ultraPerfMode", "Ultra Performance Mode");
        builder.add("wathe.midnightconfig.ultraPerfMode.tooltip", "Disables scenery for a worse visual experience but maximum performance. Lowers render distance to 2.");
        builder.add("wathe.midnightconfig.disableScreenShake", "Disable Screen Shake");

        builder.add("wathe.argument.game_mode.invalid", "Game mode could not be found");
        builder.add("wathe.argument.map_effect.invalid", "Map effect could not be found");

        builder.add("credits.wathe.thank_you", "Thank you for playing The Last Voyage of the Harpy Express!\nMe and my team spent a lot of time working\non this mod and we hope you enjoy it.\nIf you do and wish to make a video or stream\nplease make sure to credit my channel,\nvideo and the mod page!\n - RAT / doctor4t");
    }
}
