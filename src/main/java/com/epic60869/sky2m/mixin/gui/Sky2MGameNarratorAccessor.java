package com.epic60869.sky2m.mixin.gui;

import com.mojang.text2speech.Narrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The text to speech behind the game's narrator, for Voice Callouts. */
@Mixin(GameNarrator.class)
public interface Sky2MGameNarratorAccessor {
    @Accessor("narrator")
    Narrator sky2m$narrator();
}
