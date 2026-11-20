package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MLocation;
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
        // Drawn as real text (the gizmo text the other world labels use has one colour, so these came out all white),
        // through blocks like SkyHanni's, on a dark backing so it reads over any ground.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SlayerItemsOnGround::render);
    }

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        if (labels.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;
        net.minecraft.world.phys.Vec3 cam = com.epic60869.sky2m.sim.compat.McRender.cameraPos(context);
        com.mojang.blaze3d.vertex.PoseStack pose = context.poseStack();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Map.Entry<ItemEntity, Label> entry : labels.entrySet()) {
            ItemEntity item = entry.getKey();
            if (item.isRemoved()) continue;
            net.minecraft.world.phys.Vec3 at = item.getPosition(partial).add(0, 0.8, 0);
            // A little bigger further away, so a far label stays readable.
            float scale = 0.035f * (float) Math.max(1.0, Math.sqrt(at.distanceToSqr(cam)) / 8.0);
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            pose.mulPose(new org.joml.Matrix4f().rotation(com.epic60869.sky2m.sim.compat.McRender.cameraRotation(context)));
            pose.scale(scale, -scale, scale);
            Component text = entry.getValue().text();
            com.epic60869.sky2m.sim.compat.McRender.drawText(context, font, text, -font.width(text) / 2f, -font.lineHeight / 2f,
                0xFFFFFFFF, false, pose, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0x70000000, 0xF000F0);
            pose.popPose();
        }
    }

    /**
     * The item's name in its rarity colour, as SkyHanni names it: the item list's name (coloured by its rarity), since
     * Hypixel's dropped items don't always carry a coloured name; else the item's own name.
     */
    private static Component name(String id, ItemStack stack) {
        String repo = com.epic60869.sky2m.custom.RepoItems.displayName(id);
        ChatFormatting colour = com.epic60869.sky2m.custom.RepoItems.tierColour(com.epic60869.sky2m.custom.RepoItems.tier(id));
        if (repo != null && !repo.isBlank()) {
            return repo.startsWith("§") ? Component.literal(repo) : Component.literal(repo).withStyle(colour);
        }
        Component own = stack.getHoverName();
        return own.getStyle().getColor() != null ? own.copy() : Component.literal(own.getString()).withStyle(colour);
    }

    /** "5x Tarantula Web (12.3k)" with the name in its rarity colour, or null when it's worth less than {@code minimum}. */
    private static Component label(ItemStack stack, int minimum) {
        if (stack.isEmpty() || stack.getItem() instanceof SpawnEggItem) return null;
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return null;
        double unit = Math.max(ItemPriceResolver.value(id), ItemPriceResolver.npcPrice(id));
        double total = unit * stack.getCount();
        if (total <= 0 || total < minimum) return null;
        MutableComponent text = Component.empty();
        if (stack.getCount() > 1) text.append(Component.literal(stack.getCount() + "x ").withStyle(ChatFormatting.YELLOW));
        return text.append(name(id, stack))
            .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(CombatFeatures.formatCoins(total)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
    }
}
