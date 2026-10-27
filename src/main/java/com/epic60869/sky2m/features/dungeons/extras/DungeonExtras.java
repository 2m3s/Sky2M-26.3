package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dungeons > Dungeon Extras: the config and start-up of a set of smaller dungeon features, ported from SkyHanni
 * (https://github.com/hannibal002/SkyHanni, LGPL-2.1), Odin (https://github.com/odtheking/Odin, BSD-3-Clause),
 * NoFrills (https://github.com/WhatYouThing/NoFrills, BSD-3-Clause) and Skyblocker (LGPL-3.0). Each feature lives in
 * its own class in this package; everything is off by default unless harmless, and display only.
 */
public final class DungeonExtras {
    private static int ticks;

    private DungeonExtras() {}

    /** The config, or null before it has loaded. */
    public static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.dungeonExtras;
    }

    /** From the sound packet mixin: Spring Boots charge sounds. */
    public static void onSound(String name, float pitch) {
        SpringBootsHelper.onSound(name, pitch);
    }

    /** From the sound packet mixin: whether Clean End mutes this sound. */
    public static boolean cancelSound(String name) {
        return DungeonClutter.cancelSound(name);
    }

    /** From the entity render mixin: whether Hide Dungeon Clutter or Clean End skip drawing this entity. */
    public static boolean hide(net.minecraft.world.entity.Entity entity) {
        return DungeonClutter.hide(entity);
    }

    public static void init() {
        SpringBootsHelper.init();
        DoorRoomLabels.init();
        DungeonCreationCooldown.init();
        CroesusOverlay.init();
        DungeonClutter.init();
        DungeonChatTweaks.init();
        com.epic60869.sky2m.features.dungeons.extras.QuickClose.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks % 20 == 0) syncWaypointTypes();
        });
    }

    /**
     * Copies the secret waypoint toggles into the Skyblocker config the ported secret waypoints read (the room database
     * and its item pickup, chest, bat, lever and other secrets come from the port).
     */
    private static void syncWaypointTypes() {
        Config c = config();
        if (c == null) return;
        var w = SkyblockerConfigManager.get().dungeons.secretWaypoints;
        Types t = c.secretTypes;
        w.enableEntranceWaypoints = t.entrance;
        w.enableSuperboomWaypoints = t.superboom;
        w.enableChestWaypoints = t.chest;
        w.enableItemWaypoints = t.item;
        w.enableBatWaypoints = t.bat;
        w.enableWitherWaypoints = t.wither;
        w.enableRedstoneKeyWaypoints = t.redstoneKey;
        w.enableLeverWaypoints = t.lever;
        w.enableFairySoulWaypoints = t.fairySoul;
        w.enableStonkWaypoints = t.stonk;
        w.enableAotvWaypoints = t.aotv;
        w.enablePearlWaypoints = t.pearl;
        w.enablePrinceWaypoints = t.prince;
        w.enableDefaultWaypoints = t.other;
        w.showSecretText = t.showText;
        w.adaptiveBoxSize = t.adaptiveBoxSize;
    }

    public static final class Config {
        @Expose
        @Accordion
        @ConfigOption(name = "Secret Waypoint Types", desc = "Which kinds of secrets the room database draws waypoints for (Skyblocker's): chests, wither essence, levers, bats, redstone keys and item pickups (Candycomb, Decoy, Defuse Kit, Spirit Leap... picked up from the floor, marked found when you pick them up). Turn on Secret Waypoints under Secrets and Routes first.")
        public Types secretTypes = new Types();

        @Expose
        @Accordion
        @ConfigOption(name = "Door Room Labels", desc = "A label on each door of the room you are in, with the name of the room behind it (and its secrets) once it has been identified.")
        public DoorLabels doorLabels = new DoorLabels();

        @Expose
        @Accordion
        @ConfigOption(name = "Spring Boots", desc = "Odin's and SkyHanni's Spring Boots helper: how high your jump will be while you charge it, and a title when it is fully charged.")
        public SpringBoots springBoots = new SpringBoots();

        @Expose
        @Accordion
        @ConfigOption(name = "Creation Cooldown", desc = "SkyHanni's Dungeon Creation Cooldown: after you start a dungeon, Hypixel won't let you create another for 30 seconds. Shows the time left and can block /joininstance meanwhile.")
        public CreationCooldown creationCooldown = new CreationCooldown();

        @Expose
        @Accordion
        @ConfigOption(name = "Croesus Overlay", desc = "SkyHanni's Croesus Chest Tracker: Croesus's runs coloured by opened or unopened, a check on runs you used a Kismet Feather on, your unopened chests out of Croesus's limit of 60 on screen, and your Kismet Feathers on the Reroll Chest.")
        public Croesus croesus = new Croesus();

        @Expose
        @Accordion
        @ConfigOption(name = "Hide Dungeon Clutter", desc = "SkyHanni's Dungeon Object Hider: hide healer orbs and fairies, blessings, Superboom TNT, Revive Stones, Premium Flesh, skeleton skulls, Soulweaver skulls and Journal Entries.")
        public Clutter clutter = new Clutter();

        @Expose
        @Accordion
        @ConfigOption(name = "Clean End", desc = "SkyHanni's Clean End: once the boss is dead, hide everything except the loot chests and mute the boss music.")
        public CleanEnd cleanEnd = new CleanEnd();

        @Expose
        @Accordion
        @ConfigOption(name = "Chat Tweaks", desc = "SkyHanni's solo class message hider and Milestones Display.")
        public Chat chat = new Chat();

        @Expose
        @Accordion
        @ConfigOption(name = "Quick Close", desc = "NoFrills' Quick Close: close dungeon chests (secret and reward chests) by pressing a movement key (W, A, S or D).")
        public QuickClose quickClose = new QuickClose();

        @Expose
        @Accordion
        @ConfigOption(name = "Shard Score Message", desc = "NoFrills' Shard Score Message: send a message when an attribute gives you Bonus Score by a Prince or a Bat dying.")
        public ShardScore shardScore = new ShardScore();
    }

    public static final class Types {
        @Expose @ConfigOption(name = "Entrance", desc = "Where the room's entrance is.") @ConfigEditorBoolean public boolean entrance = true;
        @Expose @ConfigOption(name = "Chests", desc = "Secret chests.") @ConfigEditorBoolean public boolean chest = true;
        @Expose @ConfigOption(name = "Item Pickups", desc = "Secret items on the floor (Candycomb, Decoy, Defuse Kit, Dungeon Chest Key, Healing VIII, Inflatable Jerry, Spirit Leap, Training Weights, Trap, Treasure Talisman). Marked as found when you pick the item up.") @ConfigEditorBoolean public boolean item = true;
        @Expose @ConfigOption(name = "Bats", desc = "Secret bats.") @ConfigEditorBoolean public boolean bat = true;
        @Expose @ConfigOption(name = "Wither Essence", desc = "Wither essence secrets.") @ConfigEditorBoolean public boolean wither = true;
        @Expose @ConfigOption(name = "Redstone Keys", desc = "Redstone key secrets.") @ConfigEditorBoolean public boolean redstoneKey = true;
        @Expose @ConfigOption(name = "Levers", desc = "Levers.") @ConfigEditorBoolean public boolean lever = true;
        @Expose @ConfigOption(name = "Superboom", desc = "Where to use a Superboom TNT.") @ConfigEditorBoolean public boolean superboom = true;
        @Expose @ConfigOption(name = "Fairy Souls", desc = "Fairy souls.") @ConfigEditorBoolean public boolean fairySoul = true;
        @Expose @ConfigOption(name = "Stonk", desc = "Where to stonk.") @ConfigEditorBoolean public boolean stonk = true;
        @Expose @ConfigOption(name = "AOTV", desc = "Where to teleport with an Aspect of the Void.") @ConfigEditorBoolean public boolean aotv = true;
        @Expose @ConfigOption(name = "Pearls", desc = "Where to throw an Ender Pearl.") @ConfigEditorBoolean public boolean pearl = true;
        @Expose @ConfigOption(name = "Prince", desc = "The Prince's spots.") @ConfigEditorBoolean public boolean prince = true;
        @Expose @ConfigOption(name = "Other", desc = "Waypoints of no particular type.") @ConfigEditorBoolean public boolean other = true;
        @Expose @ConfigOption(name = "Show Text", desc = "The name of the secret on its waypoint.") @ConfigEditorBoolean public boolean showText = true;
        @Expose @ConfigOption(name = "Adaptive Box Size", desc = "Make the waypoint's box fit the block it is on (a chest's box is smaller than a full block).") @ConfigEditorBoolean public boolean adaptiveBoxSize = false;
    }

    public static final class DoorLabels {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Label each door of the room you are in with the room behind it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Secrets", desc = "Also how many secrets you have found in that room (2/5).")
        @ConfigEditorBoolean
        public boolean secrets = true;

        @Expose
        @ConfigOption(name = "Text Scale", desc = "Size of the label.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 4f, minStep = 0.1f)
        public float scale = 1.5f;

        @Expose
        @ConfigOption(name = "Max Distance", desc = "Only label doors this many blocks away.")
        @ConfigEditorSlider(minValue = 8, maxValue = 64, minStep = 1)
        public float distance = 40;
    }

    public static final class SpringBoots {
        @Expose
        @ConfigOption(name = "Jump Height HUD", desc = "While you crouch in Spring Boots, a HUD with how many blocks you will jump (Odin's). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Height Box", desc = "A red box where you would land the top of the jump (Odin's).")
        @ConfigEditorBoolean
        public boolean box = false;

        @Expose
        @ConfigOption(name = "Ready Title", desc = "A title and a beep when the boots are charged (SkyHanni's: in the F7/M7 boss room).")
        @ConfigEditorBoolean
        public boolean readyTitle = false;
    }

    public static final class CreationCooldown {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the time left until you can create another dungeon.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Outside Dungeons", desc = "Also show it on the rest of SkyBlock, not just in the dungeon entrance.")
        @ConfigEditorBoolean
        public boolean showOutside = true;

        @Expose
        @ConfigOption(name = "Only At The Entrance", desc = "In a dungeon, only show it before the run has started.")
        @ConfigEditorBoolean
        public boolean entranceOnly = true;

        @Expose
        @ConfigOption(name = "Block /joininstance", desc = "Don't send /joininstance (what the dungeon menus and Party Finder run) while the cooldown is up.")
        @ConfigEditorBoolean
        public boolean block = false;

        @Expose
        @ConfigOption(name = "Chat Message", desc = "A message in chat when the cooldown is over.")
        @ConfigEditorBoolean
        public boolean chatMessage = true;
    }

    public static final class Croesus {
        @Expose
        @ConfigOption(name = "SkyHanni Style", desc = "Croesus's list of runs coloured like SkyHanni's: unopened runs dark purple, runs with a chest opened dark aqua, runs you used a key on not coloured, and a check on runs you used a Kismet Feather on. Replaces the colours under Chest Profit while on (the profit colours of a run's chests stay).")
        @ConfigEditorBoolean
        public boolean skyHanniStyle = true;

        @Expose
        @ConfigOption(name = "Unopened Colour", desc = "Croesus runs with no chest opened yet (SkyHanni Style).")
        @ConfigEditorColour
        public String unopenedColour = "0:153:170:0:170";

        @Expose
        @ConfigOption(name = "Opened Colour", desc = "Croesus runs with a chest opened, a key still to use (SkyHanni Style).")
        @ConfigEditorColour
        public String openedColour = "0:153:0:170:170";

        @Expose
        @ConfigOption(name = "Unopened Chests Overlay", desc = "SkyHanni's Croesus overlay: \"Chests: n/60\", the dungeon runs you have not opened a chest of, out of Croesus's 60 (older runs, over 3 days, drop off). Shown in the Dungeon Hub, in dungeons and at the Forgotten Skull. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean overlay = false;

        @Expose
        @ConfigOption(name = "Limit Warning", desc = "Warn in chat when a run ends and 55 or more runs are waiting in Croesus (it holds 60).")
        @ConfigEditorBoolean
        public boolean limitWarning = false;

        @Expose
        @ConfigOption(name = "Kismet Used Check", desc = "A green check on runs in Croesus you used a Kismet Feather on.")
        @ConfigEditorBoolean
        public boolean kismetCheck = true;

        @Expose
        @ConfigOption(name = "Kismet Feather Count", desc = "How many Kismet Feathers you carry on the Reroll Chest in a reward chest, as its stack size. Sacks are not counted.")
        @ConfigEditorBoolean
        public boolean kismetCount = true;
    }

    public static final class Clutter {
        @Expose @ConfigOption(name = "Healer Orbs", desc = "Hide the Healer's damage, ability and defense orbs and their names.") @ConfigEditorBoolean public boolean healerOrbs = false;
        @Expose @ConfigOption(name = "Healer Fairy", desc = "Hide the Healer's fairy.") @ConfigEditorBoolean public boolean healerFairy = false;
        @Expose @ConfigOption(name = "Blessings", desc = "Hide the blessings that drop in a room.") @ConfigEditorBoolean public boolean blessings = false;
        @Expose @ConfigOption(name = "Superboom TNT", desc = "Hide dropped Superboom TNT (and its particles).") @ConfigEditorBoolean public boolean superboom = false;
        @Expose @ConfigOption(name = "Revive Stones", desc = "Hide dropped Revive Stones (and their particles).") @ConfigEditorBoolean public boolean reviveStones = false;
        @Expose @ConfigOption(name = "Premium Flesh", desc = "Hide dropped Premium Flesh.") @ConfigEditorBoolean public boolean premiumFlesh = false;
        @Expose @ConfigOption(name = "Skeleton Skulls", desc = "Hide the skulls skeletons throw, except while they move.") @ConfigEditorBoolean public boolean skeletonSkulls = false;
        @Expose @ConfigOption(name = "Soulweaver Skulls", desc = "Hide the Soulweaver's skulls.") @ConfigEditorBoolean public boolean soulweaverSkulls = false;
        @Expose @ConfigOption(name = "Journal Entries", desc = "Hide dropped Journal Entries.") @ConfigEditorBoolean public boolean journalEntries = false;
    }

    public static final class CleanEnd {
        @Expose
        @ConfigOption(name = "Enabled", desc = "After the boss dies and until the loot chests appear, hide every entity but you, and mute the boss music.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Keep F3 Guardians", desc = "On F3 and M3, keep drawing the Guardians while you crouch, so you can still kill them after the boss.")
        @ConfigEditorBoolean
        public boolean f3Guardians = false;
    }

    public static final class Chat {
        @Expose
        @ConfigOption(name = "Hide Solo Class Message", desc = "Hide \"Your Mage stats are doubled because you are the only player using this class!\" in dungeons (SkyHanni).")
        @ConfigEditorBoolean
        public boolean hideSoloClass = false;

        @Expose
        @ConfigOption(name = "Hide Solo Class Stats", desc = "Hide the boosted class stats Hypixel lists when the dungeon starts and you play your class alone (the lines starting with [Mage], [Healer]...) (SkyHanni).")
        @ConfigEditorBoolean
        public boolean hideSoloStats = false;

        @Expose
        @ConfigOption(name = "Milestones Display", desc = "SkyHanni's Milestones Display: your class milestone (0 to 3) in a HUD, and hides Hypixel's milestone lines from chat. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean milestones = false;
    }

    public static final class QuickClose {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Close a dungeon chest by pressing W, A, S or D (your movement keys).")
        @ConfigEditorBoolean
        public boolean enabled = false;
    }

    public static final class ShardScore {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Send a message when a Prince or a Bat adds Bonus Score from an attribute (\"A Prince falls. +1 Bonus Score\").")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Send Prince", desc = "Send the Prince message.")
        @ConfigEditorBoolean
        public boolean sendPrince = true;

        @Expose
        @ConfigOption(name = "Prince Message", desc = "What to send. Start it with a command like /pc to run it.")
        @ConfigEditorText
        public String princeMessage = "/pc Prince Killed!";

        @Expose
        @ConfigOption(name = "Send Bat", desc = "Send the Bat message.")
        @ConfigEditorBoolean
        public boolean sendBat = true;

        @Expose
        @ConfigOption(name = "Bat Message", desc = "What to send. Start it with a command like /pc to run it.")
        @ConfigEditorText
        public String batMessage = "/pc Bat Killed!";
    }
}
