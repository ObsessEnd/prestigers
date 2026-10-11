package com.github.tensuraprestigefix.handler;

import com.github.hvnbael.trnightmare.capability.SoulData;
import com.github.hvnbael.trnightmare.config.mechanic.UltimateSlotConfig;
import com.github.hvnbael.trnightmare.util.SoulCapabilityProvider;
import com.github.hvnbael.trnightmare.util.UltimateSlotHelper;
import com.github.tensuraprestigefix.TensuraPrestigeFix;
import com.github.tensuraprestigefix.config.PrestigeFixConfig;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.player.ITensuraPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents TRNightmare's character reset hooks from wiping Ultimate Skill Slots back to 1 on Prestige,
 * and activates TRNightmare's dormant {@link UltimateSlotHelper#onResetCounter(Player)} scaling logic.
 *
 * <h3>Root Cause Summary:</h3>
 * <ol>
 *   <li>{@code UltimateSlotHelper.onResetCounter(Player)} exists in TRNightmare to grant extra Ultimate Skill Slots
 *       upon character resets, but it is dead code (never invoked anywhere in TRNightmare).</li>
 *   <li>When a player uses a Character Reset Scroll to Prestige, TRNightmare calls
 *       {@code SoulCapabilityProvider.onCharacterReset(ServerPlayer)} <b>twice</b>:
 *       once synchronously via {@code TensuraResetScrollSoulMixin} at the end of {@code ResetScrollItem.resetEverything},
 *       and a second time on the next server tick via {@code NightmareRaceResetHooks.onResetScrollConsumed}
 *       ({@code serverPlayer.server.execute(() -> SoulCapabilityProvider.onCharacterReset(serverPlayer))}).</li>
 *   <li>{@code UltimateSlotHelper.onCharacterReset(Player)} sets {@code soulData.setUltimateSlots(-1)} and deletes
 *       all {@code "trnightmare:ultimate_slot_*"} persistent NBT tags, causing {@code getSlotCount(Player)} to
 *       re-initialize slots to {@code DefaultUltimateSlots = 1}.</li>
 * </ol>
 */
public final class UltimateSlotPrestigeHandler {

    private static final String TRNIGHTMARE_MOD_ID = "trnightmare";

    public static final String TAG_SELF_NAMING = "trnightmare:ultimate_slot_self_naming";
    public static final String TAG_AWAKENING = "trnightmare:ultimate_slot_awakening";
    public static final String TAG_RESET_PROGRESS = "trnightmare:ultimate_slot_reset_progress";
    public static final String TAG_RESET_EARNED = "trnightmare:ultimate_slot_reset_earned";

    private static final Map<UUID, PrestigeSlotSnapshot> ACTIVE_SNAPSHOTS = new ConcurrentHashMap<>();

    private UltimateSlotPrestigeHandler() {
    }

    public static boolean isTrNightmareLoaded() {
        return ModList.get().isLoaded(TRNIGHTMARE_MOD_ID);
    }

    /**
     * Captures the player's current Ultimate Skill Slot count and milestone NBT tags before
     * any reset or Prestige logic runs.
     */
    public static void captureSnapshotBeforeReset(Player player, boolean explicitPrestigeIntent) {
        if (player == null || player.level().isClientSide() || !isTrNightmareLoaded()) {
            return;
        }
        InternalTrNightmareBridge.captureSnapshot(player, explicitPrestigeIntent);
    }

    /**
     * Marks an active snapshot as a confirmed Prestige and applies slot preservation + Prestige scaling.
     */
    public static void onPrestigeConfirmed(ServerPlayer player) {
        if (player == null || player.level().isClientSide() || !isTrNightmareLoaded()) {
            return;
        }
        InternalTrNightmareBridge.handlePrestigeConfirmed(player);
    }

    /**
     * Called at the end of {@code ResetScrollItem.resetEverything(ServerPlayer)}.
     */
    public static void onAfterResetEverything(ServerPlayer player) {
        if (player == null || player.level().isClientSide() || !isTrNightmareLoaded()) {
            return;
        }
        InternalTrNightmareBridge.handleAfterResetEverything(player);
    }

    /**
     * Called at the tail of {@code UltimateSlotHelper.onCharacterReset(Player)}.
     * Intercepts both the synchronous reset call and the delayed next-tick {@code server.execute(...)} call
     * from {@code NightmareRaceResetHooks.onResetScrollConsumed}.
     */
    public static void onAfterTrNightmareCharacterReset(Player player) {
        if (player == null || player.level().isClientSide() || !isTrNightmareLoaded()) {
            return;
        }
        InternalTrNightmareBridge.handleAfterTrNightmareCharacterReset(player);
    }

    /**
     * Called every server tick to enforce post-Prestige slot state during the protection window
     * and clean up expired snapshots.
     */
    public static void onServerTickPost(MinecraftServer server) {
        if (ACTIVE_SNAPSHOTS.isEmpty() || !isTrNightmareLoaded()) {
            return;
        }
        InternalTrNightmareBridge.tickActiveSnapshots(server);
    }

    /**
     * Called when a player logs in to optionally recover missing Prestige Ultimate Slots that were
     * wiped prior to installing this mod.
     */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        if (player == null || player.level().isClientSide() || !isTrNightmareLoaded()) {
            return;
        }
        if (!PrestigeFixConfig.AUTO_RECOVER_PRESTIGE_SLOTS_ON_LOGIN.get()) {
            return;
        }
        InternalTrNightmareBridge.recoverMissingPrestigeSlotsIfNeeded(player);
    }

    public static int getPlayerResetCounter(Player player) {
        if (player == null) {
            return 0;
        }
        ITensuraPlayer playerData = TensuraStorages.getPlayerDataFrom(player);
        return playerData != null ? playerData.getResetCounter() : 0;
    }

    /**
     * Snapshot of a player's Ultimate Skill Slot state across a Prestige/Reset transaction.
     */
    public static final class PrestigeSlotSnapshot {
        public final UUID playerUuid;
        public final long capturedGameTime;
        public final int resetCounterBefore;
        public final int slotsBefore;
        public final int resetProgressBefore;
        public final int resetEarnedBefore;
        public final boolean selfNamingBefore;
        public final boolean awakeningBefore;

        public boolean prestigeConfirmed;
        public boolean scalingApplied;
        public int targetSlotsAfter;
        public int targetResetProgress;
        public int targetResetEarned;
        public boolean targetSelfNaming;
        public boolean targetAwakening;
        public int guardTicksRemaining;

        public PrestigeSlotSnapshot(
                UUID playerUuid,
                long capturedGameTime,
                int resetCounterBefore,
                int slotsBefore,
                int resetProgressBefore,
                int resetEarnedBefore,
                boolean selfNamingBefore,
                boolean awakeningBefore
        ) {
            this.playerUuid = playerUuid;
            this.capturedGameTime = capturedGameTime;
            this.resetCounterBefore = resetCounterBefore;
            this.slotsBefore = slotsBefore;
            this.resetProgressBefore = resetProgressBefore;
            this.resetEarnedBefore = resetEarnedBefore;
            this.selfNamingBefore = selfNamingBefore;
            this.awakeningBefore = awakeningBefore;
            this.prestigeConfirmed = false;
            this.scalingApplied = false;
            this.targetSlotsAfter = slotsBefore;
            this.targetResetProgress = resetProgressBefore;
            this.targetResetEarned = resetEarnedBefore;
            this.targetSelfNaming = selfNamingBefore;
            this.targetAwakening = awakeningBefore;
            this.guardTicksRemaining = 40; // Guard for 2 seconds (40 ticks) to outlive any deferred server.execute() hooks
        }
    }

    /**
     * Isolated bridge referencing TRNightmare classes.
     */
    public static final class InternalTrNightmareBridge {

        private static void captureSnapshot(Player player, boolean explicitPrestigeIntent) {
            UUID uuid = player.getUUID();
            long now = player.level().getGameTime();
            PrestigeSlotSnapshot existing = ACTIVE_SNAPSHOTS.get(uuid);

            // If we already have an active snapshot from the current transaction window, keep it
            if (existing != null && (now - existing.capturedGameTime) <= 10L) {
                if (explicitPrestigeIntent) {
                    existing.prestigeConfirmed = true;
                }
                return;
            }

            int currentSlots = UltimateSlotHelper.getSlotCount(player);
            int resetCounter = getPlayerResetCounter(player);
            CompoundTag tag = player.getPersistentData();
            int resetProgress = Math.max(0, tag.getInt(TAG_RESET_PROGRESS));
            int resetEarned = Math.max(0, tag.getInt(TAG_RESET_EARNED));
            boolean selfNaming = tag.getBoolean(TAG_SELF_NAMING);
            boolean awakening = tag.getBoolean(TAG_AWAKENING);

            PrestigeSlotSnapshot snapshot = new PrestigeSlotSnapshot(
                    uuid,
                    now,
                    resetCounter,
                    currentSlots,
                    resetProgress,
                    resetEarned,
                    selfNaming,
                    awakening
            );
            if (explicitPrestigeIntent) {
                snapshot.prestigeConfirmed = true;
            }
            ACTIVE_SNAPSHOTS.put(uuid, snapshot);

            TensuraPrestigeFix.LOGGER.debug(
                    "[TensuraPrestigeFix] Captured Ultimate Slot snapshot for {}: slots={}, resetCounter={}, resetEarned={}, resetProgress={}",
                    player.getGameProfile().getName(), currentSlots, resetCounter, resetEarned, resetProgress
            );
        }

        private static void handlePrestigeConfirmed(ServerPlayer player) {
            captureSnapshot(player, true);
            PrestigeSlotSnapshot snapshot = ACTIVE_SNAPSHOTS.get(player.getUUID());
            if (snapshot == null) {
                return;
            }
            snapshot.prestigeConfirmed = true;
            applyPostPrestigeCalculationsIfNeeded(player, snapshot);
            enforceTargetState(player, snapshot);
        }

        private static void handleAfterResetEverything(ServerPlayer player) {
            PrestigeSlotSnapshot snapshot = ACTIVE_SNAPSHOTS.get(player.getUUID());
            int currentResetCounter = getPlayerResetCounter(player);

            if (snapshot != null) {
                if (currentResetCounter > snapshot.resetCounterBefore) {
                    snapshot.prestigeConfirmed = true;
                }
                if (snapshot.prestigeConfirmed) {
                    applyPostPrestigeCalculationsIfNeeded(player, snapshot);
                    enforceTargetState(player, snapshot);
                    // Also ensure FTB Quests are reset if Prestige occurred via ResetScrollItem
                    FtbQuestsPrestigeResetHandler.onPlayerPrestige(player);
                } else if (PrestigeFixConfig.PRESERVE_ULTIMATE_SLOTS_ON_ANY_FULL_RESET.get()) {
                    enforceTargetState(player, snapshot);
                }
            }
        }

        private static void handleAfterTrNightmareCharacterReset(Player player) {
            PrestigeSlotSnapshot snapshot = ACTIVE_SNAPSHOTS.get(player.getUUID());
            if (snapshot == null) {
                return;
            }
            int currentResetCounter = getPlayerResetCounter(player);
            if (currentResetCounter > snapshot.resetCounterBefore) {
                snapshot.prestigeConfirmed = true;
            }

            if (snapshot.prestigeConfirmed) {
                if (player instanceof ServerPlayer serverPlayer) {
                    applyPostPrestigeCalculationsIfNeeded(serverPlayer, snapshot);
                }
                enforceTargetState(player, snapshot);
            } else if (PrestigeFixConfig.PRESERVE_ULTIMATE_SLOTS_ON_ANY_FULL_RESET.get()) {
                enforceTargetState(player, snapshot);
            } else {
                // Even on a non-prestige reset, preserve reset_progress and reset_earned so prior Prestige progress is not lost!
                CompoundTag tag = player.getPersistentData();
                tag.putInt(TAG_RESET_PROGRESS, snapshot.targetResetProgress);
                tag.putInt(TAG_RESET_EARNED, snapshot.targetResetEarned);
            }
        }

        private static void applyPostPrestigeCalculationsIfNeeded(ServerPlayer player, PrestigeSlotSnapshot snapshot) {
            if (snapshot.scalingApplied) {
                return;
            }
            snapshot.scalingApplied = true;
            snapshot.guardTicksRemaining = 40;

            int defaultSlots = Math.max(0, UltimateSlotConfig.INSTANCE.DefaultUltimateSlots.get());
            int baseSlots = PrestigeFixConfig.PRESERVE_ULTIMATE_SLOTS_ON_PRESTIGE.get()
                    ? Math.max(defaultSlots, snapshot.slotsBefore)
                    : defaultSlots;

            boolean resetMilestones = PrestigeFixConfig.RESET_AWAKENING_AND_NAMING_MILESTONES_ON_PRESTIGE.get();
            snapshot.targetSelfNaming = !resetMilestones && snapshot.selfNamingBefore;
            snapshot.targetAwakening = !resetMilestones && snapshot.awakeningBefore;

            int resetProgress = snapshot.resetProgressBefore;
            int resetEarned = snapshot.resetEarnedBefore;
            int gainedFromResetScaling = 0;

            if (PrestigeFixConfig.TRIGGER_TRNIGHTMARE_RESET_SLOT_SCALING.get()
                    && UltimateSlotConfig.INSTANCE.UltimateSlotResetGain.get()) {
                int earn = Math.max(0, UltimateSlotConfig.INSTANCE.UltimateSlotResetEarn.get());
                int rate = Math.max(1, UltimateSlotConfig.INSTANCE.UltimateSlotResetEarnRate.get());
                int configuredOverrideMax = PrestigeFixConfig.OVERRIDE_MAX_ULTIMATE_SLOTS_FROM_PRESTIGE.get();
                int maxEarned = configuredOverrideMax >= 0
                        ? configuredOverrideMax
                        : Math.max(0, UltimateSlotConfig.INSTANCE.UltimateSlotResetMaxEarned.get());

                if (earn > 0 && (maxEarned == 0 || resetEarned < maxEarned)) {
                    resetProgress++;
                    if (resetProgress >= rate) {
                        int cycles = resetProgress / rate;
                        resetProgress %= rate;
                        int toGrant = cycles * earn;
                        if (maxEarned > 0) {
                            toGrant = Math.min(toGrant, Math.max(0, maxEarned - resetEarned));
                        }
                        if (toGrant > 0) {
                            resetEarned += toGrant;
                            gainedFromResetScaling += toGrant;
                        }
                    }
                }
            }

            int extraBonus = Math.max(0, PrestigeFixConfig.EXTRA_ULTIMATE_SLOTS_PER_PRESTIGE.get());
            int totalGained = gainedFromResetScaling + extraBonus;

            snapshot.targetResetProgress = resetProgress;
            snapshot.targetResetEarned = resetEarned;
            snapshot.targetSlotsAfter = Math.max(0, baseSlots + totalGained);

            enforceTargetState(player, snapshot);

            TensuraPrestigeFix.LOGGER.info(
                    "[TensuraPrestigeFix] Prestige Ultimate Slots updated for {}: before={}, gained={}, after={} (resetEarned={}, resetProgress={})",
                    player.getGameProfile().getName(),
                    snapshot.slotsBefore,
                    totalGained,
                    snapshot.targetSlotsAfter,
                    snapshot.targetResetEarned,
                    snapshot.targetResetProgress
            );

            if (PrestigeFixConfig.NOTIFY_PLAYER_ON_SLOT_RESTORE.get()) {
                if (totalGained > 0) {
                    player.sendSystemMessage(
                            Component.translatable("message.trnightmare.ultimate_slot_gained.reset", totalGained, snapshot.targetSlotsAfter)
                                    .withStyle(ChatFormatting.AQUA)
                    );
                } else {
                    player.sendSystemMessage(
                            Component.literal("[Prestige] ")
                                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                                    .append(Component.literal("Preserved your " + snapshot.targetSlotsAfter + " Ultimate Skill Slot(s).")
                                            .withStyle(ChatFormatting.AQUA))
                    );
                }
            }
        }

        private static void enforceTargetState(Player player, PrestigeSlotSnapshot snapshot) {
            CompoundTag tag = player.getPersistentData();
            tag.putInt(TAG_RESET_PROGRESS, snapshot.targetResetProgress);
            tag.putInt(TAG_RESET_EARNED, snapshot.targetResetEarned);
            if (snapshot.targetSelfNaming) {
                tag.putBoolean(TAG_SELF_NAMING, true);
            } else {
                tag.remove(TAG_SELF_NAMING);
            }
            if (snapshot.targetAwakening) {
                tag.putBoolean(TAG_AWAKENING, true);
            } else {
                tag.remove(TAG_AWAKENING);
            }

            SoulData soulData = SoulCapabilityProvider.resolve(player);
            int clampedTarget = Math.max(0, snapshot.targetSlotsAfter);
            if (soulData.getUltimateSlots() != clampedTarget) {
                soulData.setUltimateSlots(clampedTarget);
                SoulCapabilityProvider.persist(player, soulData);
            }
        }

        private static void tickActiveSnapshots(MinecraftServer server) {
            Iterator<Map.Entry<UUID, PrestigeSlotSnapshot>> iterator = ACTIVE_SNAPSHOTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PrestigeSlotSnapshot> entry = iterator.next();
                PrestigeSlotSnapshot snapshot = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(snapshot.playerUuid);
                if (player != null && (snapshot.prestigeConfirmed || PrestigeFixConfig.PRESERVE_ULTIMATE_SLOTS_ON_ANY_FULL_RESET.get())) {
                    enforceTargetState(player, snapshot);
                }
                snapshot.guardTicksRemaining--;
                if (snapshot.guardTicksRemaining <= 0) {
                    iterator.remove();
                }
            }
        }

        private static void recoverMissingPrestigeSlotsIfNeeded(ServerPlayer player) {
            int resetCounter = getPlayerResetCounter(player);
            if (resetCounter <= 0) {
                return;
            }
            if (!UltimateSlotConfig.INSTANCE.UltimateSlotResetGain.get()) {
                return;
            }

            int earn = Math.max(0, UltimateSlotConfig.INSTANCE.UltimateSlotResetEarn.get());
            int rate = Math.max(1, UltimateSlotConfig.INSTANCE.UltimateSlotResetEarnRate.get());
            int configuredOverrideMax = PrestigeFixConfig.OVERRIDE_MAX_ULTIMATE_SLOTS_FROM_PRESTIGE.get();
            int maxEarned = configuredOverrideMax >= 0
                    ? configuredOverrideMax
                    : Math.max(0, UltimateSlotConfig.INSTANCE.UltimateSlotResetMaxEarned.get());

            if (earn <= 0) {
                return;
            }

            int expectedCycles = resetCounter / rate;
            int expectedProgress = resetCounter % rate;
            int expectedEarnedFromResets = expectedCycles * earn;
            if (maxEarned > 0) {
                expectedEarnedFromResets = Math.min(expectedEarnedFromResets, maxEarned);
            }
            int extraPerPrestige = Math.max(0, PrestigeFixConfig.EXTRA_ULTIMATE_SLOTS_PER_PRESTIGE.get());
            int expectedExtraBonus = resetCounter * extraPerPrestige;

            CompoundTag tag = player.getPersistentData();
            int recordedEarned = Math.max(0, tag.getInt(TAG_RESET_EARNED));
            int currentSlots = UltimateSlotHelper.getSlotCount(player);
            int defaultSlots = Math.max(0, UltimateSlotConfig.INSTANCE.DefaultUltimateSlots.get());

            // Minimum slots a player with this many Prestiges should have
            int minimumExpectedSlots = Math.max(0, defaultSlots + expectedEarnedFromResets + expectedExtraBonus);

            if (currentSlots < minimumExpectedSlots || recordedEarned < expectedEarnedFromResets) {
                int missingEarned = Math.max(0, expectedEarnedFromResets - recordedEarned);
                int newSlots = Math.max(minimumExpectedSlots, Math.max(0, currentSlots + missingEarned));

                tag.putInt(TAG_RESET_EARNED, Math.max(recordedEarned, expectedEarnedFromResets));
                tag.putInt(TAG_RESET_PROGRESS, expectedProgress);
                SoulData soulData = SoulCapabilityProvider.resolve(player);
                soulData.setUltimateSlots(newSlots);
                SoulCapabilityProvider.persist(player, soulData);

                TensuraPrestigeFix.LOGGER.info(
                        "[TensuraPrestigeFix] Recovered missing Prestige Ultimate Slots for {} (resetCounter={}): {} -> {} slots.",
                        player.getGameProfile().getName(), resetCounter, currentSlots, newSlots
                );

                if (PrestigeFixConfig.NOTIFY_PLAYER_ON_SLOT_RESTORE.get()) {
                    player.sendSystemMessage(
                            Component.literal("[Prestige] ")
                                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                                    .append(Component.literal("Restored your Ultimate Skill Slots to " + newSlots + " based on your Prestige level (" + resetCounter + ").")
                                            .withStyle(ChatFormatting.AQUA))
                    );
                }
            }
        }
    }
}
