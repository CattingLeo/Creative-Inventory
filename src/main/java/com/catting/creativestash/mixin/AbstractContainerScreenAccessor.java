package com.catting.creativestash.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// leftPos/topPos/imageWidth/imageHeight/hoveredSlot are protected on the vanilla
// class - this exposes them to our non-subclass client code.
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int getLeftPos();

    // lets us push the vanilla inventory window aside, the same way vanilla's own
    // recipe book does, to make room for our panel instead of floating over it
    @Accessor("leftPos")
    void setLeftPos(int value);

    @Accessor("topPos")
    int getTopPos();

    @Accessor("imageWidth")
    int getImageWidth();

    @Accessor("imageHeight")
    int getImageHeight();

    @Accessor("hoveredSlot")
    Slot getHoveredSlot();
}
