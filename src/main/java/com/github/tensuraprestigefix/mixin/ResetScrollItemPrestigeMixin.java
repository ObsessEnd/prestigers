package com.github.tensuraprestigefix.mixin;

import com.github.tensuraprestigefix.handler.UltimateSlotPrestigeHandler;
import io.github.manasmods.tensura.item.misc.ResetScrollItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link ResetScrollItem} with high priority before SlimeThroneExtras and TRNightmare mixins
 * so the player's pre-Prestige Ultimate Skill Slot count and resetCounter are captured before any reset runs,
 * and restored/scaled immediately after {@code resetEverything} completes.
 */
@Mixin(value = ResetScrollItem.class, priority = 500, remap = false)
public abstract class ResetScrollItemPrestigeMixin {

    @Inject(method = "releaseUsing", at = @At("HEAD"), remap = false)
    private void tensuraPrestigeFix$beforeReleaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft, CallbackInfo ci) {
        if (!level.isClientSide() && entity instanceof ServerPlayer serverPlayer) {
            UltimateSlotPrestigeHandler.captureSnapshotBeforeReset(serverPlayer, false);
        }
    }

    @Inject(method = "resetEverything", at = @At("HEAD"), remap = false)
    private static void tensuraPrestigeFix$beforeResetEverything(ServerPlayer player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.captureSnapshotBeforeReset(player, false);
        }
    }

    @Inject(method = "resetEverything", at = @At("TAIL"), remap = false)
    private static void tensuraPrestigeFix$afterResetEverything(ServerPlayer player, CallbackInfo ci) {
        if (player != null && !player.level().isClientSide()) {
            UltimateSlotPrestigeHandler.onAfterResetEverything(player);
        }
    }
}
