package com.github.tensuraprestigefix.handler;

import com.github.tensuraprestigefix.TensuraPrestigeFix;
import com.github.tensuraprestigefix.config.PrestigeFixConfig;
import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.net.ClearRepeatCooldownMessage;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resets a player's FTB Quests progress, claimed rewards (both player-scoped and team-scoped),
 * and repeatable quest cooldowns when they Prestige, then immediately synchronizes the updated
 * {@link TeamData} to the client.
 */
public final class FtbQuestsPrestigeResetHandler {

    private static final String FTB_QUESTS_MOD_ID = "ftbquests";

    /**
     * Tracks the last server tick a player's quests were reset via Prestige to avoid duplicate execution
     * when both SlimeThroneExtras' PrestigeUtils.doPrestige and ResetScrollItem.resetEverything fire
     * during the same Prestige action.
     */
    private static final Map<UUID, Long> LAST_RESET_GAME_TIME = new ConcurrentHashMap<>();

    private FtbQuestsPrestigeResetHandler() {
    }

    public static boolean isFtbQuestsLoaded() {
        return ModList.get().isLoaded(FTB_QUESTS_MOD_ID);
    }

    /**
     * Triggered automatically when a player Prestiges.
     *
     * @param player the prestiging ServerPlayer
     * @return the number of quests reset, or 0 if skipped/disabled
     */
    public static int onPlayerPrestige(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return 0;
        }
        if (!PrestigeFixConfig.RESET_FTB_QUESTS_ON_PRESTIGE.get()) {
            return 0;
        }
        if (!isFtbQuestsLoaded()) {
            return 0;
        }

        long now = player.serverLevel().getGameTime();
        Long lastTick = LAST_RESET_GAME_TIME.get(player.getUUID());
        if (lastTick != null && Math.abs(now - lastTick) <= 5L) {
            return 0;
        }
        LAST_RESET_GAME_TIME.put(player.getUUID(), now);

        return resetPlayerQuestsAndRewards(player, true);
    }

    /**
     * Performs a complete reset of FTB Quests task progress, completion timestamps, claimed rewards,
     * and repeat cooldowns for the given player, respecting any excluded chapter/quest IDs in config.
     *
     * @param player       target ServerPlayer
     * @param notifyPlayer whether to send a chat notification to the player
     * @return number of quests reset
     */
    public static int resetPlayerQuestsAndRewards(ServerPlayer player, boolean notifyPlayer) {
        if (!isFtbQuestsLoaded() || player == null) {
            return 0;
        }
        return InternalFtbQuestsBridge.resetQuestsInternal(player, notifyPlayer);
    }

    /**
     * Isolated inner class so FTB Quests classes are only loaded by the JVM when ftbquests is present.
     */
    private static final class InternalFtbQuestsBridge {

        private static int resetQuestsInternal(ServerPlayer player, boolean notifyPlayer) {
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            if (file == null) {
                file = ServerQuestFile.getInstance().orElse(null);
            }
            if (file == null) {
                TensuraPrestigeFix.LOGGER.warn("[TensuraPrestigeFix] Cannot reset FTB Quests for {}: ServerQuestFile is null.", player.getGameProfile().getName());
                return 0;
            }

            Optional<TeamData> teamDataOpt = file.getTeamData(player);
            if (teamDataOpt.isEmpty()) {
                TensuraPrestigeFix.LOGGER.warn("[TensuraPrestigeFix] Cannot reset FTB Quests for {}: No TeamData found.", player.getGameProfile().getName());
                return 0;
            }

            TeamData teamData = teamDataOpt.get();
            boolean wasLocked = teamData.isLocked();
            if (wasLocked) {
                teamData.setLocked(false);
            }

            Set<Long> excludedChapters = parseHexIdSet(PrestigeFixConfig.EXCLUDED_CHAPTER_IDS.get());
            Set<Long> excludedQuests = parseHexIdSet(PrestigeFixConfig.EXCLUDED_QUEST_IDS.get());

            boolean resetClaimedRewards = PrestigeFixConfig.RESET_CLAIMED_REWARDS.get();
            boolean resetTeamRewards = PrestigeFixConfig.RESET_TEAM_WIDE_REWARDS.get();
            boolean resetCooldowns = PrestigeFixConfig.RESET_REPEAT_COOLDOWNS.get();

            int resetQuestCount = 0;
            int resetRewardCount = 0;
            boolean anySkipped = false;

            UUID playerUuid = player.getUUID();

            for (Chapter chapter : file.getAllChapters()) {
                if (excludedChapters.contains(chapter.id)) {
                    anySkipped = true;
                    continue;
                }

                boolean chapterHadExcludedQuest = false;
                for (Quest quest : chapter.getQuests()) {
                    if (excludedQuests.contains(quest.id)) {
                        chapterHadExcludedQuest = true;
                        anySkipped = true;
                        continue;
                    }

                    // 1. Standard FTB Quests progress reset (resets started, completed, tasks, and player rewards)
                    ProgressChange progressChange = new ProgressChange(quest, playerUuid).setReset(true);
                    quest.forceProgressRaw(teamData, progressChange);

                    // 2. Ensure all tasks are explicitly zeroed
                    for (Task task : quest.getTasks()) {
                        teamData.setProgress(task, 0L);
                        teamData.setStarted(task.id, null);
                        teamData.setCompleted(task.id, null);
                    }

                    // 3. Explicitly reset claimed rewards (both per-player and team-wide NIL_UUID rewards)
                    if (resetClaimedRewards) {
                        for (Reward reward : quest.getRewards()) {
                            if (teamData.resetReward(playerUuid, reward)) {
                                resetRewardCount++;
                            }
                            if (resetTeamRewards) {
                                if (teamData.resetReward(Util.NIL_UUID, reward)) {
                                    resetRewardCount++;
                                }
                                teamData.deleteReward(reward);
                            }
                        }
                    }

                    // 4. Clear repeatable quest cooldowns
                    if (resetCooldowns) {
                        teamData.clearRepeatCooldown(quest);
                        ClearRepeatCooldownMessage.sendToTeam(quest, teamData.getTeamId());
                    }

                    teamData.setStarted(quest.id, null);
                    teamData.setCompleted(quest.id, null);
                    resetQuestCount++;
                }

                if (!chapterHadExcludedQuest) {
                    teamData.setStarted(chapter.id, null);
                    teamData.setCompleted(chapter.id, null);
                }
            }

            if (!anySkipped) {
                teamData.setStarted(file.id, null);
                teamData.setCompleted(file.id, null);
            }

            if (wasLocked) {
                teamData.setLocked(true);
            }

            teamData.clearCachedProgress();
            teamData.markDirty();
            teamData.saveIfChanged();

            // Send full TeamData sync packet to player so the client Quest Book UI updates immediately
            try {
                NetworkManager.sendToPlayer(player, new SyncTeamDataMessage(teamData));
            } catch (Exception e) {
                TensuraPrestigeFix.LOGGER.debug("[TensuraPrestigeFix] Failed to send SyncTeamDataMessage to {}: {}", player.getGameProfile().getName(), e.getMessage());
            }

            TensuraPrestigeFix.LOGGER.info(
                    "[TensuraPrestigeFix] Reset {} FTB Quests (and {} claimed reward entries) for player {} ({}) on Prestige.",
                    resetQuestCount, resetRewardCount, player.getGameProfile().getName(), playerUuid
            );

            if (notifyPlayer && PrestigeFixConfig.NOTIFY_PLAYER_ON_QUEST_RESET.get()) {
                player.sendSystemMessage(
                        Component.literal("[Prestige] ")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                                .append(Component.literal("Your FTB Quests progress and claimed rewards have been reset for your new Prestige run!")
                                        .withStyle(ChatFormatting.YELLOW))
                );
            }

            return resetQuestCount;
        }

        private static Set<Long> parseHexIdSet(List<? extends String> rawHexIds) {
            Set<Long> result = new HashSet<>();
            if (rawHexIds == null) {
                return result;
            }
            for (String raw : rawHexIds) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String cleaned = raw.trim();
                if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                    cleaned = cleaned.substring(2);
                }
                QuestObjectBase.parseHexId(cleaned).ifPresent(result::add);
            }
            return result;
        }
    }
}
