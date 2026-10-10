// Ported from Firmament (https://github.com/FirmamentMC/Firmament), mixins/customgui/OriginalSlotCoords.java.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.storage.CustomGui;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Remembers where a slot was before a custom gui moved it, to put it back. Slot.x/y are made mutable in sky2m.classtweaker. */
@Mixin(Slot.class)
public class Sky2MSlotCoordsMixin implements CustomGui.RememberingSlot {
    @Shadow public int x;
    @Shadow public int y;
    @Unique private int sky2m$originalX;
    @Unique private int sky2m$originalY;

    @Override
    public void sky2m$rememberCoords() {
        this.sky2m$originalX = this.x;
        this.sky2m$originalY = this.y;
    }

    @Override
    public void sky2m$restoreCoords() {
        this.x = this.sky2m$originalX;
        this.y = this.sky2m$originalY;
    }
}
