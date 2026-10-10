// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.screen;

import com.epic60869.sky2m.custom.util.Compat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.CustomAnimatedHelmetTextures;
import com.epic60869.sky2m.custom.CustomHelmetTextures;
import com.epic60869.sky2m.custom.util.SearchableGridWidget;

public class HeadSelectionWidget extends SearchableGridWidget {
	private static final Identifier INNER_SPACE_TEXTURE = Compat.id("menu_inner_space");

	private final List<HeadButton> allButtons = new ArrayList<>();
	private final HeadButton noneButton;

	private @Nullable ItemStack currentItem;
	/**
	 * Null if a custom (animated or not) head wasn't selected.
	 */
	private HeadButton selectedButton;

	public HeadSelectionWidget(int x, int y, int width, int height) {
		super(x + 2, y + 2, width - 4, height - 4, Component.nullToEmpty("HeadSelection"), 20, true);

		// The "no custom skin" barrier goes first so it is always easy to find.
		this.noneButton = new HeadButton("", this::onClick);
		this.selectedButton = this.noneButton;
		this.allButtons.add(this.noneButton);

		for (CustomHelmetTextures.NamedTexture tex : CustomHelmetTextures.getTextures()) {
			ItemStack head = Compat.createSkull(tex.texture());
			HeadButton button = new HeadButton(tex.name(), tex.texture(), head, this::onClick);
			this.allButtons.add(button);
		}

		for (String id : CustomAnimatedHelmetTextures.getAnimatedHeadIds()) {
			AnimatedHeadButton button = new AnimatedHeadButton(id, this::onClick);
			this.allButtons.add(button);
		}

		setSearch("");
	}

	@Override
	public void setX(int x) {
		super.setX(x + 2);
	}

	@Override
	public void setY(int y) {
		super.setY(y + 2);
	}

	@Override
	public void setWidth(int width) {
		super.setWidth(width - 4);
	}

	@Override
	public void setHeight(int height) {
		super.setHeight(height - 4);
	}

	private void onClick(HeadButton button) {
		selectedButton = button;
		updateConfig();
		updateButtons();
	}

	private void updateConfig() {
		if (this.currentItem == null) return;
		String uuid = Compat.uuid(this.currentItem);

		CustomConfigManager.updateOnly(config -> {
			switch (this.selectedButton) {
				// Animated heads have no single texture, so they must be matched before the
				// "no texture means remove" case below, or selecting one would clear the skin.
				case AnimatedHeadButton button -> {
					config.general.customAnimatedHelmetTextures.put(uuid, button.id);
					config.general.customHelmetTextures.remove(uuid);
				}
				case HeadButton button when button == noneButton || button.texture == null -> {
					config.general.customHelmetTextures.remove(uuid);
					config.general.customAnimatedHelmetTextures.remove(uuid);
				}
				case HeadButton button -> {
					config.general.customHelmetTextures.put(uuid, Objects.requireNonNull(button.texture));
					config.general.customAnimatedHelmetTextures.remove(uuid);
				}
			}
		});
	}

	private void updateButtons() {
		// Check all buttons, whether one is selected depends on if it matches the selectedButton
		// noneButton is included
		for (HeadButton b : this.allButtons) {
			b.selected = b.equals(this.selectedButton);
		}
	}

	@Override
	protected Collection<? extends AbstractWidget> filterWidgets(String search) {
		setScrollAmount(0);
		updateButtons();
		String s = search.toLowerCase(Locale.ENGLISH);
		return allButtons.stream().filter(b -> b == noneButton || b.name.toLowerCase(Locale.ENGLISH).contains(s)).toList();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INNER_SPACE_TEXTURE, getX() - 2, getY() - 2, getWidth() + 4, getHeight() + 4);
		super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
	}

	public void setCurrentItem(ItemStack item) {
		this.currentItem = item;
		String uuid = Compat.uuid(item);

		String animatedHeadId = CustomConfigManager.get().general.customAnimatedHelmetTextures.get(uuid);
		String customHeadTexture = CustomConfigManager.get().general.customHelmetTextures.get(uuid);
		// The head button that should be selected (if any)
		HeadButton intendedSelected;

		// Search for the right button to select, defaulting to null if a suitable one cannot be found (e.g. texture changed, animated head removed)
		if (animatedHeadId != null) {
			intendedSelected = this.allButtons.stream()
					.filter(AnimatedHeadButton.class::isInstance)
					.map(AnimatedHeadButton.class::cast)
					.filter(animatedHead -> animatedHead.id.equals(animatedHeadId))
					.findFirst()
					.map(HeadButton.class::cast)
					.orElse(noneButton);
		} else if (customHeadTexture != null) {
			intendedSelected = this.allButtons.stream()
					.filter(Predicate.not(AnimatedHeadButton.class::isInstance))
					.filter(head -> head.texture != null && head.texture.equals(customHeadTexture))
					.findFirst()
					.orElse(noneButton);
		} else {
			intendedSelected = noneButton;
		}

		this.selectedButton = intendedSelected;

		updateButtons();
	}

	private static class HeadButton extends AbstractWidget {
		private final String name;
		/**
		 * Only null if this is an animated head.
		 */
		private final @Nullable String texture;
		/**
		 * Only null if this is an animated head.
		 */
		private final ItemStack head;
		private final Consumer<HeadButton> onPress;
		protected boolean selected = false;

		private HeadButton(String name, Consumer<HeadButton> onPress) {
			this(name, null, Compat.barrier(), onPress);
		}

		private HeadButton(String name, @Nullable String texture, ItemStack head, Consumer<HeadButton> onPress) {
			super(0, 0, 20, 20, Component.empty());
			this.name = name;
			this.texture = texture;
			this.head = head;
			this.onPress = onPress;

			if (!name.isEmpty()) {
				setTooltip(Tooltip.create(Component.nullToEmpty(name)));
			}
		}

		/**
		 * Retrieves the underlying {@link ItemStack} for displaying the head, required for animated heads.
		 */
		protected ItemStack getHead() {
			// Re-read the profile so a head whose skin failed to load is retried (Sky2M).
			if (this.texture != null) this.head.set(DataComponents.PROFILE, CustomHelmetTextures.getProfile(this.texture));
			return this.head;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			graphics.item(this.getHead(), getX() + 2, getY() + 2);
			if (this.selected) {
				graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x3000FF00);
			}
			if (this.isHovered()) {
				graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x20FFFFFF);
			}
			this.handleCursor(graphics);
		}

		@Override
		public void onClick(MouseButtonEvent click, boolean doubled) {
			this.onPress.accept(this);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {}
	}

	private static class AnimatedHeadButton extends HeadButton {
		private final String id;

		private AnimatedHeadButton(String id, Consumer<HeadButton> onPress) {
			super(CustomAnimatedHelmetTextures.formatName(id), onPress);
			this.id = id;
		}

		/**
		 * Creates the item stack dynamically for each entry as the underlying stack will need to change.
		 */
		@Override
		protected ItemStack getHead() {
			// Only animate the head you are looking at; the rest show their first frame, so opening
			// the picker does not start downloading thousands of animation frames (Sky2M).
			ResolvableProfile profile = this.selected || this.isHovered()
					? CustomAnimatedHelmetTextures.animateHeadTexture(this.id)
					: CustomAnimatedHelmetTextures.firstFrame(this.id);

			if (profile != null) {
				ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
				stack.set(DataComponents.PROFILE, profile);

				return stack;
			}

			return Compat.barrier();
		}
	}
}
