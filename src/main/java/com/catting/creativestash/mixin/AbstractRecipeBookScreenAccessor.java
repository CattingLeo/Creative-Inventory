package com.catting.creativestash.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// recipeBookComponent is private - this lets us force it closed while our own
// panel is open, since it renders/handles clicks fully independently of the
// crafting grid it normally sits next to.
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor {
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> getRecipeBookComponent();
}
