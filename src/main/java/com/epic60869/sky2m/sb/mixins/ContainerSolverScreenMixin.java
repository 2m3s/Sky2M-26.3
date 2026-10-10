// Mixins supporting Sky2M's port of Skyblocker's dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import com.epic60869.sky2m.sb.utils.container.ContainerSolver;
import com.epic60869.sky2m.sb.utils.container.ContainerSolverManager;
import com.epic60869.sky2m.sb.utils.container.StackDisplayModifier;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Draws solver highlights and forwards slot clicks, as in Skyblocker's AbstractContainerScreenMixin. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSolverScreenMixin {
	// Drawn right after the slots (the pose is already translated to the container) rather than in extractTooltip,
	// which other mods cancel inside terminals to hide item tooltips.
	@Inject(method = "extractSlots", at = @At("TAIL"))
	private void sky2m$drawSolverHighlights(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (com.epic60869.sky2m.features.dungeons.OdinTerminals.active()) {
			com.epic60869.sky2m.features.dungeons.OdinTerminals.render(graphics, screen);
			return;
		}
		ContainerSolverManager.onExtract(graphics, screen, screen.getMenu().slots);
	}

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"), cancellable = true)
	private void sky2m$onSlotClicked(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		// Sky2M: locked slots and slot binds.
		if (com.epic60869.sky2m.features.misc.SlotLocking.onSlotClicked((AbstractContainerScreen<?>) (Object) this, slot, button, input)) {
			ci.cancel();
			return;
		}
		com.epic60869.sky2m.features.misc.PricePaid.onSlotClicked((AbstractContainerScreen<?>) (Object) this, slot);
		// Sky2M: visitor reward warning (blocked offer buttons) and the Supercraft button.
		if (com.epic60869.sky2m.features.garden.visitor.GardenVisitors.onSlotClicked((AbstractContainerScreen<?>) (Object) this, slot, input)) {
			ci.cancel();
			return;
		}
		// Sky2M: Odin's terminal solver takes every click in a terminal (misclick and first-click protection).
		if (com.epic60869.sky2m.features.dungeons.OdinTerminals.active()) {
			if (com.epic60869.sky2m.features.dungeons.OdinTerminals.onSlotClicked(slot == null ? slotId : slot.index, button)) ci.cancel();
			return;
		}
		if (slot == null || ContainerSolverManager.getCurrentSolver() == null) return;
		if (ContainerSolverManager.onSlotClick(slotId, slot.getItem(), button)) ci.cancel();
	}

	@Shadow
	protected Slot hoveredSlot;

	// Solvers that change what a slot shows (e.g. Superpairs showing the cards you've revealed), from Skyblocker's
	// AbstractContainerScreenMixin.
	@ModifyArg(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;III)V"))
	private ItemStack sky2m$displayStackFake(ItemStack stack, @Local(argsOnly = true) Slot slot) {
		return sky2m$displayStack(slot, stack);
	}

	@ModifyArg(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;III)V"))
	private ItemStack sky2m$displayStackReal(ItemStack stack, @Local(argsOnly = true) Slot slot) {
		return sky2m$displayStack(slot, stack);
	}

	@ModifyArg(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"))
	private ItemStack sky2m$displayStackDecorations(ItemStack stack, @Local(argsOnly = true) Slot slot) {
		return sky2m$displayStack(slot, stack);
	}

	@ModifyVariable(method = "extractTooltip", at = @At(value = "STORE"), ordinal = 0)
	private ItemStack sky2m$displayTooltipStack(ItemStack stack) {
		return hoveredSlot == null ? stack : sky2m$displayStack(hoveredSlot, stack);
	}

	private ItemStack sky2m$displayStack(Slot slot, ItemStack stack) {
		ContainerSolver solver = ContainerSolverManager.getCurrentSolver();
		if (solver instanceof StackDisplayModifier modifier && solver.isSolverSlot(slot, (AbstractContainerScreen<?>) (Object) this)) {
			return modifier.modifyDisplayStack(slot.getContainerSlot(), stack);
		}
		return stack;
	}
}
