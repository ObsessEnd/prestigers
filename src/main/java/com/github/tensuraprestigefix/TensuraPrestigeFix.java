package com.github.tensuraprestigefix;

import com.github.tensuraprestigefix.command.PrestigeFixCommands;
import com.github.tensuraprestigefix.config.PrestigeFixConfig;
import com.github.tensuraprestigefix.handler.FtbQuestsPrestigeResetHandler;
import com.github.tensuraprestigefix.handler.UltimateSlotPrestigeHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.crypticdev.stextras.event.STPlayerEvents;
import org.slf4j.Logger;

/**
 * Main entry point for Tensura Prestige Fix (NeoForge 1.21.1).
 *
 * <p>Addresses two bugs when players Prestige in the Tensura: Reincarnated + SlimeThroneExtras +
 * TRNightmare + FTB Quests modpack stack:</p>
 * <ol>
 *   <li><b>FTB Quests Rewards & Progress Not Resetting:</b> Automatically resets quest completion,
 *       task progress, claimed rewards (both personal and team-shared), and repeat cooldowns on Prestige,
 *       followed by an immediate {@code SyncTeamDataMessage} packet to the client.</li>
 *   <li><b>TRNightmare Ultimate Skill Slots Resetting to 1:</b> Prevents TRNightmare's synchronous
 *       and next-tick deferred {@code UltimateSlotHelper.onCharacterReset} hooks from wiping earned
 *       Ultimate Skill Slots back to 1 on Prestige, while activating TRNightmare's unreferenced
 *       {@code UltimateSlotHelper.onResetCounter} scaling logic.</li>
 * </ol>
 */
@Mod(TensuraPrestigeFix.MOD_ID)
public class TensuraPrestigeFix {

    public static final String MOD_ID = "tensura_prestige_fix";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String STEXTRAS_MOD_ID = "stextras";

    public TensuraPrestigeFix(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, PrestigeFixConfig.SPEC);

        modEventBus.addListener(this::onCommonSetup);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded(STEXTRAS_MOD_ID)) {
                StExtrasEventBridge.registerEvents();
                LOGGER.info("[TensuraPrestigeFix] Hooked SlimeThroneExtras STPlayerEvents.PRESTIGE listener.");
            } else {
                LOGGER.info("[TensuraPrestigeFix] SlimeThroneExtras not detected; relying on ResetScrollItem Prestige detection.");
            }
        });
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        PrestigeFixCommands.register(event.getDispatcher());
    }

    private void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            UltimateSlotPrestigeHandler.onPlayerLoggedIn(serverPlayer);
        }
    }

    private void onServerTickPost(final ServerTickEvent.Post event) {
        UltimateSlotPrestigeHandler.onServerTickPost(event.getServer());
    }

    /**
     * Isolated bridge for SlimeThroneExtras Architectury events so classloading only occurs when stextras is present.
     */
    private static final class StExtrasEventBridge {
        private static void registerEvents() {
            STPlayerEvents.PRESTIGE.register(player -> {
                if (player != null && !player.level().isClientSide()) {
                    LOGGER.info("[TensuraPrestigeFix] Detected STPlayerEvents.PRESTIGE for player {} ({}).",
                            player.getGameProfile().getName(), player.getUUID());
                    UltimateSlotPrestigeHandler.onPrestigeConfirmed(player);
                    FtbQuestsPrestigeResetHandler.onPlayerPrestige(player);
                }
            });

            STPlayerEvents.PRESTIGE_LEVEL.register((player, level) -> {
                if (player != null && !player.level().isClientSide()) {
                    // Note: SlimeThroneExtras fires PRESTIGE_LEVEL on EVERY ResetScrollItem.resetEverything call
                    // (including non-prestige resets at level=0 and race selection in the Reincarnation GUI).
                    // Do NOT treat PRESTIGE_LEVEL as a Prestige trigger; only enforce already-preserved slot state.
                    LOGGER.debug("[TensuraPrestigeFix] Observed STPlayerEvents.PRESTIGE_LEVEL (level={}) for player {} ({}).",
                            level, player.getGameProfile().getName(), player.getUUID());
                    UltimateSlotPrestigeHandler.onPostResetSync(player);
                }
            });
        }
    }
}
