package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.OdinDevices;
import net.minecraft.client.Minecraft;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Arrow Align's Block Wrong Clicks: cancels a right click on an entity before it's sent, like Odin's MinecraftMixin. */
@Mixin(Minecraft.class)
public abstract class Sky2MEntityUseMixin {
    @Shadow
    @Nullable
    public HitResult hitResult;

    @Shadow
    public abstract void startUseItem();

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void sky2m$leftClickLeap(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !Sky2MLocation.inDungeon()) return;
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.leapMenu.enabled) return;
        ItemStack held = mc.player.getMainHandItem();
        String name = net.minecraft.ChatFormatting.stripFormatting(held.getHoverName().getString());
        if (name == null) return;
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.contains("spirit leap") && !normalized.contains("infinileap")) return;
        startUseItem();
        cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void sky2m$blockEntityUse(CallbackInfo ci) {
        if (hitResult instanceof EntityHitResult entityHit && OdinDevices.onUseEntity(entityHit.getEntity())) ci.cancel();
    }
}
