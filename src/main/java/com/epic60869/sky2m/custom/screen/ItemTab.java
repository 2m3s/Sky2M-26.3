// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.screen;

import com.epic60869.sky2m.custom.util.Compat;
import java.util.List;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.TriState;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.screen.name.CustomizeNameWidget;

public class ItemTab extends GridLayoutTab {
	private static final Identifier INNER_SPACE_TEXTURE = Compat.id("menu_inner_space");

	private final CustomizeScreen parentScreen;
	private final CustomizeNameWidget nameWidget;
	private final Button glintButton;
	private final IdentifierTextField modelField;

	private ItemStack currentItem = ItemStack.EMPTY;
	private TriState glintState = TriState.DEFAULT;

	public ItemTab(CustomizeScreen parentScreen) {
		super(Component.translatable("sky2m.customization.item"));
		layout.spacing(5);
		this.parentScreen = parentScreen;
		glintButton = Button.builder(Component.empty(), b -> {
			TriState[] states = TriState.values();
			glintState = states[(glintState.ordinal() + 1) % states.length];
			b.setMessage(getGlintText());
			String uuid = Compat.uuid(currentItem);
			CustomConfigManager.updateOnly(config -> {
				Object2BooleanMap<String> customGlint = config.general.customGlint;
				switch (glintState) {
					case DEFAULT -> customGlint.removeBoolean(uuid);
					case TRUE -> customGlint.put(uuid, true);
					case FALSE -> customGlint.put(uuid, false);
				}
			});
		}).width(Button.SMALL_WIDTH).build();
		modelField = new IdentifierTextField(120, 20, identifier -> {
			String uuid = Compat.uuid(currentItem);
			if (uuid.isEmpty()) return;
			CustomConfigManager.updateOnly(config -> {
				if (identifier == null) config.general.customItemModel.remove(uuid);
				else config.general.customItemModel.put(uuid, identifier);
			});
		});
		modelField.setHint(Component.translatable("sky2m.customization.item.modelOverride").withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.GRAY));
		nameWidget = new CustomizeNameWidget(parentScreen);

		layout.addChild(new ItemSelector(), 0, 0, 1, 1);
		layout.addChild(new BackgroundRenderer(), 0, 1);

		LinearLayout linearLayout = layout.addChild(LinearLayout.vertical(), 0, 1, p -> p.alignHorizontallyRight().paddingRight(3).paddingVertical(3));
		linearLayout.addChild(glintButton);
		linearLayout.addChild(Button.builder(Component.translatable("sky2m.customization.item.selectModel"), _ -> {
			Minecraft minecraft = Minecraft.getInstance();
			Consumer<@Nullable Identifier> applyItemModel = stack -> {
				if (stack != null) {
					modelField.setValue(stack.toString());
				}
			};
			minecraft.gui.setScreen(new ModelSelectionPopup(parentScreen, applyItemModel));
		}).width(120).build(), p -> p.paddingTop(4));
		linearLayout.addChild(modelField);

		layout.addChild(nameWidget, 1, 0, 1, 2);

		LocalPlayer player = Minecraft.getInstance().player;
		ItemStack handStack = player.getMainHandItem();
		if (!Compat.uuid(handStack).isEmpty()) {
			setCurrentItem(handStack);
			return;
		}
		for (ItemStack stack : Compat.getCurrentEquipmentSet()) {
			if (!Compat.uuid(stack).isEmpty()) {
				setCurrentItem(stack);
				return;
			}
		}
		for (ItemStack stack : player.getInventory()) {
			if (!Compat.uuid(stack).isEmpty()) {
				setCurrentItem(stack);
				return;
			}
		}
		visitChildren(clickableWidget -> clickableWidget.visible = false);
		layout.addChild(new StringWidget(Component.translatable("sky2m.customization.nothingCustomizable"), Minecraft.getInstance().font),
				0, 0, 3, 2, p -> p.alignHorizontallyCenter().alignVerticallyMiddle());
	}

	private void setCurrentItem(ItemStack itemStack) {
		this.currentItem = itemStack;
		String uuid = Compat.uuid(currentItem);
		boolean empty = uuid.isEmpty();
		visitChildren(clickableWidget -> clickableWidget.visible = !empty);
		if (empty) return;
		parentScreen.backupConfigs(itemStack);
		nameWidget.setItem(itemStack);
		if (CustomConfigManager.get().general.customItemModel.containsKey(uuid)) {
			Identifier identifier = CustomConfigManager.get().general.customItemModel.get(uuid);
			String string = identifier.toString();
			modelField.setValue(string);
		} else {
			modelField.setValue("");
		}

		// glint
		Object2BooleanMap<String> customGlint = CustomConfigManager.get().general.customGlint;
		String itemUuid = Compat.uuid(itemStack);
		if (customGlint.containsKey(itemUuid)) {
			glintState = customGlint.getBoolean(itemUuid) ? TriState.TRUE : TriState.FALSE;
		} else {
			glintState = TriState.DEFAULT;
		}
		glintButton.setMessage(getGlintText());
	}

	private Component getGlintText() {
		return Component.translatable("sky2m.customization.item.glint", switch (glintState) {
			case DEFAULT -> Component.translatable("sky2m.customization.item.glint.default");
			case TRUE -> CommonComponents.OPTION_ON;
			case FALSE -> CommonComponents.OPTION_OFF;
		});
	}

	private class ItemSelector extends AbstractContainerWidget {
		private static final int PADDING = 3;
		// Offset added to ensure this is aligned with the item model field area
		private static final int ADDED_ITEM_OFFSET = 7;

		private final Button selectItemButton;
		private final LinearLayout layout = LinearLayout.vertical().spacing(5);

		private ItemSelector() {
			super(0, 20, 0, 0, Component.literal("Item Selector"), AbstractScrollArea.defaultSettings(8));
			layout.addChild(SpacerElement.height(32 + ADDED_ITEM_OFFSET));
			selectItemButton = layout.addChild(Button.builder(Component.literal("Select Item"), _ ->
					Minecraft.getInstance().gui.setScreen(new ItemSelectPopup(parentScreen, ItemTab.this::setCurrentItem))
			).width(Button.SMALL_WIDTH).build());
			layout.arrangeElements();
			layout.setPosition(PADDING, PADDING);
			setSize(layout.getWidth() + PADDING * 2, layout.getHeight() + PADDING * 2);
		}

		@Override
		public void setX(int x) {
			super.setX(x);
			layout.setX(getX() + PADDING);
		}

		@Override
		public void setY(int y) {
			super.setY(y);
			layout.setY(getY() + PADDING);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(selectItemButton);
		}

		@Override
		protected int contentHeight() {
			return 0;
		}

		@Override
		protected double scrollRate() {
			return 0;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, INNER_SPACE_TEXTURE, getX(), getY(), getWidth(), getHeight());
			Matrix3x2fStack matrices = graphics.pose();
			matrices.pushMatrix();
			float x = layout.getX() + layout.getWidth() / 2f - 16;
			int y = layout.getY() + ADDED_ITEM_OFFSET;
			if (mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32) {
				graphics.setTooltipForNextFrame(currentItem.getHoverName(), mouseX, mouseY);
			}
			matrices.translate(x, y);
			matrices.scale(2);
			graphics.item(currentItem, 0, 0);
			matrices.popMatrix();
			selectItemButton.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {

		}
	}

	private class BackgroundRenderer extends AbstractWidget {

		BackgroundRenderer() {
			super(0, 0, 0, 0, Component.empty());
			active = false;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			int x = glintButton.getX() - 3;
			int y = glintButton.getY() - 3;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
					INNER_SPACE_TEXTURE,
					x,
					y,
					modelField.getRight() + 3 - x,
					modelField.getBottom() + 3 - y
			);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput builder) {}
	}
}
