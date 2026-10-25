package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Slayers > Items on Ground, from SkyHanni's SlayerItemsOnGround: while you have a slayer quest and are in that
 * slayer's area, items lying on the ground within 15 blocks are labelled with their name, amount and price, the higher
 * of their market and NPC price. Items under the Minimum Price aren't labelled.
 */
public final class SlayerItemsOnGround {
    private static final double RANGE = 15;
    /** A label stays this long after its item was last seen, as SkyHanni keeps it for 2 seconds. */
    private static final long KEEP_MS = 2_000L;

    private record Label(Component text, long seenAt) {}

    private static final Map<ItemEntity, Label> labels = new WeakHashMap<>();
    private static int ticks;

    private SlayerItemsOnGround() {}

    private static Sky2MConfig.ItemsOnGround config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.itemsOnGround;
    }

    private static boolean enabled() {
        Sky2MConfig.ItemsOnGround c = config();
        if (c == null || !c.enabled || !Sky2MLocation.onSkyblock()) return false;
        String quest = SlayerArea.questSlayer();
        return quest != null && quest.equals(SlayerArea.slayerHere());
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0) return;
            long now = System.currentTimeMillis();
            labels.values().removeIf(label -> now - label.seenAt() > KEEP_MS);
            if (mc.player == null || mc.level == null || !enabled()) {
                labels.clear();
                return;
            }
            int minimum = config().minimumPrice;
            for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(RANGE),
                    e -> e.distanceToSqr(mc.player) <= RANGE * RANGE)) {
                Component text = label(item.getItem(), minimum);
                if (text != null) labels.put(item, new Label(text, now));
            }
        });
        Sky2MWorldRender.register(collector -> {
            if (labels.isEmpty()) return;
            for (Map.Entry<ItemEntity, Label> entry : labels.entrySet()) {
                ItemEntity item = entry.getKey();
                if (item.isRemoved()) continue;
                collector.submitText(entry.getValue().text(), item.position().add(0, 0.8, 0), 1.0f, false);
            }
        });
    }

    /** "5x Tarantula Web (12.3k)": the name in its own colour, or null when it's worth less than {@code minimum}. */
    private static Component label(ItemStack stack, int minimum) {
        if (stack.isEmpty() || stack.getItem() instanceof SpawnEggItem) return null;
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return null;
        double unit = Math.max(ItemPriceResolver.value(id), ItemPriceResolver.npcPrice(id));
        double total = unit * stack.getCount();
        if (total <= 0 || total < minimum) return null;
        MutableComponent text = Component.empty();
        if (stack.getCount() > 1) text.append(Component.literal(stack.getCount() + "x ").withStyle(ChatFormatting.GRAY));
        return text.append(stack.getHoverName().copy())
            .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(CombatFeatures.formatCoins(total)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
    }
}
