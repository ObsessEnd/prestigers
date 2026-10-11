package com.github.tensuraprestigefix.mixin;

import com.github.tensuraprestigefix.handler.UltimateSlotPrestigeHandler;
import net.minecraft.server.level.ServerPlayer;
import org.crypticdev.stextras.utils.PrestigeUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link PrestigeUtils#doPrestige(ServerPlayer)} and {@link PrestigeUtils#resetPlayer(ServerPlayer)}
 * to ensure snapshots are captured even when Prestige is triggered directly via admin commands
 * such as {@code /tno forceprestige <player>}.
 */
@Mixin(value = PrestigeUtils.class, priority = 500, remap = false)
public abstract class PrestigeUtilsMixin {

    @Inject(method = "doPrestige", at = @At("HEAD"), remap = false)
    private static void tensuraPrestigeFix$beforeDoPrestige(ServerPlayer player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.captureSnapshotBeforeReset(player, false);
        }
    }

    @Inject(method = "resetPlayer", at = @At("HEAD"), remap = false)
    private static void tensuraPrestigeFix$beforeResetPlayer(ServerPlayer player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.captureSnapshotBeforeReset(player, false);
        }
    }
}
