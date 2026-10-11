package com.github.tensuraprestigefix.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;

/**
 * Server/Common configuration for Tensura Prestige Fix.
 * Generates config/tensura_prestige_fix-common.toml.
 */
public final class PrestigeFixConfig {

    public static final ModConfigSpec SPEC;

    // --- FTB Quests Prestige Reset Settings ---
    public static final ModConfigSpec.BooleanValue RESET_FTB_QUESTS_ON_PRESTIGE;
    public static final ModConfigSpec.BooleanValue RESET_CLAIMED_REWARDS;
    public static final ModConfigSpec.BooleanValue RESET_TEAM_WIDE_REWARDS;
    public static final ModConfigSpec.BooleanValue RESET_REPEAT_COOLDOWNS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_CHAPTER_IDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_QUEST_IDS;
    public static final ModConfigSpec.BooleanValue NOTIFY_PLAYER_ON_QUEST_RESET;

    // --- TRNightmare Ultimate Skill Slot Preservation & Scaling Settings ---
    public static final ModConfigSpec.BooleanValue PRESERVE_ULTIMATE_SLOTS_ON_PRESTIGE;
    public static final ModConfigSpec.BooleanValue PRESERVE_ULTIMATE_SLOTS_ON_ANY_FULL_RESET;
    public static final ModConfigSpec.BooleanValue TRIGGER_TRNIGHTMARE_RESET_SLOT_SCALING;
    public static final ModConfigSpec.IntValue EXTRA_ULTIMATE_SLOTS_PER_PRESTIGE;
    public static final ModConfigSpec.IntValue OVERRIDE_MAX_ULTIMATE_SLOTS_FROM_PRESTIGE;
    public static final ModConfigSpec.BooleanValue RESET_AWAKENING_AND_NAMING_MILESTONES_ON_PRESTIGE;
    public static final ModConfigSpec.BooleanValue AUTO_RECOVER_PRESTIGE_SLOTS_ON_LOGIN;
    public static final ModConfigSpec.BooleanValue NOTIFY_PLAYER_ON_SLOT_RESTORE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "=======================================================================",
                " Tensura Prestige Fix - FTB Quests Reset Configuration",
                "======================================================================="
        ).push("ftb_quests_reset");

        RESET_FTB_QUESTS_ON_PRESTIGE = builder
                .comment("If true, automatically resets a player's FTB Quests progress and claimed rewards when they Prestige.")
                .define("resetFtbQuestsOnPrestige", true);

        RESET_CLAIMED_REWARDS = builder
                .comment("If true, resets all claimed quest rewards for the prestiging player so rewards can be claimed again in the new Prestige run.")
                .define("resetClaimedRewards", true);

        RESET_TEAM_WIDE_REWARDS = builder
                .comment("If true, also resets rewards marked as 'Team Reward' (stored under NIL_UUID in FTB Quests) for the player's FTB Team.")
                .define("resetTeamWideRewards", true);

        RESET_REPEAT_COOLDOWNS = builder
                .comment("If true, clears repeatable quest cooldown timers upon Prestige.")
                .define("resetRepeatCooldowns", true);

        EXCLUDED_CHAPTER_IDS = builder
                .comment(
                        "List of FTB Quests Chapter hex IDs (e.g. [\"1A2B3C4D5E6F7A8B\"]) that should NOT be reset on Prestige.",
                        "Useful for welcome/rules chapters or permanent account-wide chapters."
                )
                .defineListAllowEmpty("excludedChapterIds", Collections.emptyList(), () -> "", obj -> obj instanceof String);

        EXCLUDED_QUEST_IDS = builder
                .comment(
                        "List of individual FTB Quest hex IDs (e.g. [\"0F1E2D3C4B5A6978\"]) that should NOT be reset on Prestige."
                )
                .defineListAllowEmpty("excludedQuestIds", Collections.emptyList(), () -> "", obj -> obj instanceof String);

        NOTIFY_PLAYER_ON_QUEST_RESET = builder
                .comment("If true, sends a chat confirmation message to the player when their FTB Quests progress and rewards are reset on Prestige.")
                .define("notifyPlayerOnQuestReset", true);

        builder.pop();

        builder.comment(
                "=======================================================================",
                " Tensura Prestige Fix - TRNightmare Ultimate Skill Slot Configuration",
                "======================================================================="
        ).push("ultimate_skill_slots");

        PRESERVE_ULTIMATE_SLOTS_ON_PRESTIGE = builder
                .comment(
                        "If true, prevents TRNightmare's character reset hooks (both synchronous and next-tick deferred hooks)",
                        "from wiping the player's Ultimate Skill Slots back to 1 when Prestiging."
                )
                .define("preserveUltimateSlotsOnPrestige", true);

        PRESERVE_ULTIMATE_SLOTS_ON_ANY_FULL_RESET = builder
                .comment(
                        "If true, also preserves Ultimate Skill Slots on a regular Character Reset Scroll full reset even if it was not a Prestige."
                )
                .define("preserveUltimateSlotsOnAnyFullReset", false);

        TRIGGER_TRNIGHTMARE_RESET_SLOT_SCALING = builder
                .comment(
                        "TRNightmare contains a method UltimateSlotHelper.onResetCounter(Player) intended to grant extra Ultimate Skill Slots",
                        "based on trnightmare-common.toml (ultimateSlotResetEarn / ultimateSlotResetEarnRate / ultimateSlotResetMaxEarned),",
                        "but TRNightmare never actually invokes it and wipes its NBT tracking tags on reset.",
                        "If true, Tensura Prestige Fix preserves those tracking tags and invokes onResetCounter(Player) once per Prestige."
                )
                .define("triggerTrNightmareResetSlotScaling", true);

        EXTRA_ULTIMATE_SLOTS_PER_PRESTIGE = builder
                .comment(
                        "Additional flat Ultimate Skill Slots granted per Prestige ON TOP OF TRNightmare's onResetCounter scaling.",
                        "Leave at 0 if you are using triggerTrNightmareResetSlotScaling = true (which already grants +1 per Prestige by default)."
                )
                .defineInRange("extraUltimateSlotsPerPrestige", 0, 0, 100);

        OVERRIDE_MAX_ULTIMATE_SLOTS_FROM_PRESTIGE = builder
                .comment(
                        "Overrides TRNightmare's ultimateSlotResetMaxEarned cap (which defaults to 3 in trnightmare-common.toml).",
                        "Set to -1 to use TRNightmare's config value, 0 for unlimited Prestige slot scaling, or a positive integer for a custom cap."
                )
                .defineInRange("overrideMaxUltimateSlotsFromPrestige", -1, -1, 1000);

        RESET_AWAKENING_AND_NAMING_MILESTONES_ON_PRESTIGE = builder
                .comment(
                        "If false (recommended when preserving total slots), keeps the 'awakening' and 'self_naming' slot milestone flags",
                        " matching the preserved slots so players do not infinitely stack Awakening/Naming slots on top of already-preserved Awakening/Naming slots.",
                        "If true, clears the 'awakening' and 'self_naming' milestone flags on Prestige so the player can earn them again in the new run."
                )
                .define("resetAwakeningAndNamingMilestonesOnPrestige", false);

        AUTO_RECOVER_PRESTIGE_SLOTS_ON_LOGIN = builder
                .comment(
                        "If true, when a player logs in or Prestiges, checks their Tensura resetCounter against their earned Prestige Ultimate Slots",
                        "and automatically restores any missing Prestige Ultimate Slots that were previously wiped by the TRNightmare reset bug."
                )
                .define("autoRecoverPrestigeSlotsOnLogin", true);

        NOTIFY_PLAYER_ON_SLOT_RESTORE = builder
                .comment("If true, sends a chat summary to the player showing their preserved/updated Ultimate Skill Slot count after Prestige.")
                .define("notifyPlayerOnSlotRestore", true);

        builder.pop();

        SPEC = builder.build();
    }

    private PrestigeFixConfig() {
    }
}
