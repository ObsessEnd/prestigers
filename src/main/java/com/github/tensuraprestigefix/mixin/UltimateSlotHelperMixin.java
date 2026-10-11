package com.github.tensuraprestigefix.mixin;

import com.github.hvnbael.trnightmare.util.UltimateSlotHelper;
import com.github.tensuraprestigefix.handler.UltimateSlotPrestigeHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link UltimateSlotHelper#onCharacterReset(Player)} in TRNightmare.
 *
 * <p>TRNightmare invokes {@code onCharacterReset} both synchronously (at the end of {@code ResetScrollItem.resetEverything})
 * and asynchronously on the next server tick (via {@code NightmareRaceResetHooks.onResetScrollConsumed}).
 * Without this mixin, both calls set {@code soulData.setUltimateSlots(-1)} and delete all
 * {@code "trnightmare:ultimate_slot_*"} NBT keys, resetting the player's Ultimate Skill Slots back to 1.</p>
 */
@Mixin(value = UltimateSlotHelper.class, priority = 1500, remap = false)
public abstract class UltimateSlotHelperMixin {

    @Inject(method = "onCharacterReset", at = @At("HEAD"), remap = false)
    private static void tensuraPrestigeFix$beforeOnCharacterReset(Player player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.captureSnapshotBeforeReset(player, false);
        }
    }

    @Inject(method = "onCharacterReset", at = @At("TAIL"), remap = false)
    private static void tensuraPrestigeFix$afterOnCharacterReset(Player player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.onAfterTrNightmareCharacterReset(player);
        }
    }
}
