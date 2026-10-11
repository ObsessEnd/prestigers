package com.github.tensuraprestigefix.command;

import com.github.hvnbael.trnightmare.capability.SoulData;
import com.github.hvnbael.trnightmare.util.SoulCapabilityProvider;
import com.github.hvnbael.trnightmare.util.UltimateSlotHelper;
import com.github.tensuraprestigefix.handler.FtbQuestsPrestigeResetHandler;
import com.github.tensuraprestigefix.handler.UltimateSlotPrestigeHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers the {@code /prestigefix} command tree for inspecting Prestige state, manually resetting
 * a player's FTB Quests rewards/progress, or recovering/setting Ultimate Skill Slots.
 */
public final class PrestigeFixCommands {

    private PrestigeFixCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("prestigefix")
                        .then(Commands.literal("status")
                                .executes(ctx -> showStatus(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> showStatus(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("resetquests")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> resetQuests(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> resetQuests(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("recoverslots")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> recoverSlots(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> recoverSlots(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("setslots")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("slots", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setSlots(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "slots")
                                                )))))
        );
    }

    private static int showStatus(CommandSourceStack source, ServerPlayer target) {
        int resetCounter = UltimateSlotPrestigeHandler.getPlayerResetCounter(target);
        int ultimateSlots = -1;
        int resetEarned = 0;
        int resetProgress = 0;
        boolean selfNaming = false;
        boolean awakening = false;

        if (UltimateSlotPrestigeHandler.isTrNightmareLoaded()) {
            ultimateSlots = TrNightmareCommandBridge.getSlots(target);
            CompoundTag tag = target.getPersistentData();
            resetEarned = tag.getInt(UltimateSlotPrestigeHandler.TAG_RESET_EARNED);
            resetProgress = tag.getInt(UltimateSlotPrestigeHandler.TAG_RESET_PROGRESS);
            selfNaming = tag.getBoolean(UltimateSlotPrestigeHandler.TAG_SELF_NAMING);
            awakening = tag.getBoolean(UltimateSlotPrestigeHandler.TAG_AWAKENING);
        }

        final int finalUltimateSlots = ultimateSlots;
        final int finalResetEarned = resetEarned;
        final int finalResetProgress = resetProgress;
        final boolean finalSelfNaming = selfNaming;
        final boolean finalAwakening = awakening;

        source.sendSuccess(() -> Component.literal("=== Tensura Prestige Fix Status: " + target.getGameProfile().getName() + " ===")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal(" • Prestige Count (resetCounter): ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(resetCounter)).withStyle(ChatFormatting.GREEN)), false);

        if (finalUltimateSlots >= 0) {
            source.sendSuccess(() -> Component.literal(" • Ultimate Skill Slots: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(finalUltimateSlots)).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)), false);
            source.sendSuccess(() -> Component.literal(" • Slots Earned via Prestige: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(finalResetEarned + " (cycle progress: " + finalResetProgress + ")").withStyle(ChatFormatting.YELLOW)), false);
            source.sendSuccess(() -> Component.literal(" • Milestones Claimed: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Self-Naming=" + finalSelfNaming + ", Awakening=" + finalAwakening).withStyle(ChatFormatting.WHITE)), false);
        } else {
            source.sendSuccess(() -> Component.literal(" • TRNightmare Loaded: false").withStyle(ChatFormatting.RED), false);
        }

        return 1;
    }

    private static int resetQuests(CommandSourceStack source, ServerPlayer target) {
        if (!FtbQuestsPrestigeResetHandler.isFtbQuestsLoaded()) {
            source.sendFailure(Component.literal("FTB Quests is not loaded on this server."));
            return 0;
        }
        int count = FtbQuestsPrestigeResetHandler.resetPlayerQuestsAndRewards(target, true);
        source.sendSuccess(() -> Component.literal("Reset " + count + " FTB Quests (progress, claimed rewards, and cooldowns) for " + target.getGameProfile().getName() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return count;
    }

    private static int recoverSlots(CommandSourceStack source, ServerPlayer target) {
        if (!UltimateSlotPrestigeHandler.isTrNightmareLoaded()) {
            source.sendFailure(Component.literal("TRNightmare is not loaded on this server."));
            return 0;
        }
        int before = TrNightmareCommandBridge.getSlots(target);
        UltimateSlotPrestigeHandler.onPlayerLoggedIn(target);
        int after = TrNightmareCommandBridge.getSlots(target);
        source.sendSuccess(() -> Component.literal("Checked/recovered Prestige Ultimate Skill Slots for " + target.getGameProfile().getName() + ": " + before + " -> " + after + " slots.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setSlots(CommandSourceStack source, ServerPlayer target, int slots) {
        if (!UltimateSlotPrestigeHandler.isTrNightmareLoaded()) {
            source.sendFailure(Component.literal("TRNightmare is not loaded on this server."));
            return 0;
        }
        int updated = TrNightmareCommandBridge.setSlots(target, slots);
        source.sendSuccess(() -> Component.literal("Set Ultimate Skill Slots for " + target.getGameProfile().getName() + " to " + updated + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static final class TrNightmareCommandBridge {
        private static int getSlots(ServerPlayer player) {
            return UltimateSlotHelper.getSlotCount(player);
        }

        private static int setSlots(ServerPlayer player, int slots) {
            int clamped = Math.max(0, slots);
            SoulData soulData = SoulCapabilityProvider.resolve(player);
            soulData.setUltimateSlots(clamped);
            SoulCapabilityProvider.persist(player, soulData);
            return clamped;
        }
    }
}
