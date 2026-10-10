package com.epic60869.sky2m.features.fishing;

import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sea creatures, rare drops and the lists the fishing settings choose from, ported from Feesh
 * (https://github.com/Sleepy-Panda/Feesh, constants/SeaCreatures.kt, constants/RareDrops.kt and settings/models/,
 * Apache-2.0).
 */
public final class FishingData {
    private FishingData() {}

    /** A sea creature's rarity: its chat colour, and Feesh's hex colour and gradient for compact catch messages. */
    public enum Rarity {
        COMMON(ChatFormatting.WHITE, 0xFFFFFF, 0xFFFFFF, 0xFFF8C8, 0xFFD4E6, 0xD0EBFF),
        UNCOMMON(ChatFormatting.GREEN, 0x54FC54, 0x54FC54, 0xA8FC54, 0x3AB03A),
        RARE(ChatFormatting.BLUE, 0x449AFC, 0x449AFC, 0x3FE4B9, 0x6DBBEA),
        EPIC(ChatFormatting.DARK_PURPLE, 0xA234EB, 0xA234EB, 0xE68CAF, 0x873285),
        LEGENDARY(ChatFormatting.GOLD, 0xFC8F00, 0xFC8F00, 0xFFB137, 0xFCCD18),
        MYTHIC(ChatFormatting.LIGHT_PURPLE, 0xFC54FC, 0xFC54FC, 0xFBAE52, 0xEB7195),
        DIVINE(ChatFormatting.AQUA, 0x54FCFC, 0x54FCFC, 0x46C0FC, 0x54FCFC),
        SPECIAL(ChatFormatting.RED, 0xFC5454, 0xFC5454, 0xB20303, 0xFC5454);

        public final ChatFormatting colour;
        public final int rgb;
        public final int[] gradient;

        Rarity(ChatFormatting colour, int rgb, int... gradient) {
            this.colour = colour;
            this.rgb = rgb;
            this.gradient = gradient;
        }
    }

    public record SeaCreature(String name, Rarity rarity, Pattern pattern, boolean rare, boolean canBeDoubleHooked) {}

    public record RareDrop(String id, RareDropType type, ChatFormatting colour, boolean extremelyRare, List<String> alternateNames) {
        public String name() {
            return type.displayName;
        }
    }

    private static final List<SeaCreature> CREATURES = new ArrayList<>();
    private static final List<RareDrop> DROPS = new ArrayList<>();

    private static void add(String name, Rarity rarity, String regex, boolean rare, boolean canBeDoubleHooked) {
        CREATURES.add(new SeaCreature(name, rarity, Pattern.compile(regex), rare, canBeDoubleHooked));
    }

    private static void drop(String id, RareDropType type, String colour, boolean extremelyRare, String... alternateNames) {
        ChatFormatting formatting = switch (colour) {
            case "COMMON" -> ChatFormatting.WHITE;
            case "UNCOMMON" -> ChatFormatting.GREEN;
            case "RARE" -> ChatFormatting.BLUE;
            case "EPIC" -> ChatFormatting.DARK_PURPLE;
            case "LEGENDARY" -> ChatFormatting.GOLD;
            case "MYTHIC" -> ChatFormatting.LIGHT_PURPLE;
            case "SPECIAL" -> ChatFormatting.RED;
            default -> ChatFormatting.valueOf(colour);
        };
        DROPS.add(new RareDrop(id, type, formatting, extremelyRare, List.of(alternateNames)));
    }

    static {
        add("Water Hydra", Rarity.LEGENDARY, "^The Water Hydra has come to test your strength\\.$", true, true);
        add("Carrot King", Rarity.RARE, "^Is this even a fish\\? It\\'s the Carrot King\\!$", true, true);
        add("Squid", Rarity.COMMON, "^A Squid appeared\\.$", false, true);
        add("Night Squid", Rarity.COMMON, "^Pitch darkness reveals a Night Squid\\.$", false, true);
        add("Sea Walker", Rarity.COMMON, "^You caught a Sea Walker\\.$", false, true);
        add("Sea Guardian", Rarity.COMMON, "^You stumbled upon a Sea Guardian\\.$", false, true);
        add("Sea Witch", Rarity.UNCOMMON, "^It looks like you\\'ve disrupted the Sea Witch\\'s brewing session\\. Watch out, she\\'s furious\\!$", false, true);
        add("Sea Archer", Rarity.UNCOMMON, "^You reeled in a Sea Archer\\.$", false, true);
        add("Rider of the Deep", Rarity.UNCOMMON, "^The Rider of the Deep has emerged\\.$", false, true);
        add("Catfish", Rarity.RARE, "^Huh\\? A Catfish\\!$", false, true);
        add("Sea Leech", Rarity.RARE, "^Gross\\! A Sea Leech\\!$", false, true);
        add("Guardian Defender", Rarity.EPIC, "^You\\'ve discovered a Guardian Defender of the sea\\.$", false, true);
        add("Deep Sea Protector", Rarity.EPIC, "^You have awoken the Deep Sea Protector, prepare for a battle\\!$", false, true);
        add("Agarimoo", Rarity.RARE, "^Your Chumcap Bucket trembles, it\\'s an Agarimoo\\.$", false, true);
        add("Inkling", Rarity.UNCOMMON, "^You get an inkling that you\\'ve caught\\.\\.\\. an Inkling!$", false, true);
        add("Manta Ray", Rarity.EPIC, "^A majestic creature rises from the water\\. It\\'s a Manta Ray\\.$", false, true);
        add("Frog Man", Rarity.COMMON, "^Is it a frog\\? Is it a man\\? Well, yes, sorta, IT\\'S FROG MAN\\!\\!\\!\\!\\!\\!$", false, true);
        add("Snapping Turtle", Rarity.RARE, "^A Snapping Turtle is coming your way, and it\\'s ANGRY\\!$", false, true);
        add("Blue Ringed Octopus", Rarity.LEGENDARY, "^A garish set of tentacles arise\\. It\\'s a Blue Ringed Octopus\\!$", true, true);
        add("Wiki Tiki", Rarity.MYTHIC, "^The water bubbles and froths\\. A massive form emerges- you have disturbed the Wiki Tiki\\! You shall pay the price\\.$", true, true);
        add("Great White Shark", Rarity.LEGENDARY, "^Hide no longer, a Great White Shark has tracked your scent and thirsts for your blood\\!$", true, true);
        add("Nurse Shark", Rarity.UNCOMMON, "^A tiny fin emerges from the water, you\\'ve caught a Nurse Shark\\.$", false, true);
        add("Blue Shark", Rarity.RARE, "^You spot a fin as blue as the water it came from, it\\'s a Blue Shark\\.$", false, true);
        add("Tiger Shark", Rarity.EPIC, "^A striped beast bounds from the depths, the wild Tiger Shark\\!$", false, true);
        add("Reindrake", Rarity.MYTHIC, "^A Reindrake forms from the depths\\.$", true, true);
        add("Yeti", Rarity.LEGENDARY, "^What is this creature\\!\\?$", true, true);
        add("Nutcracker", Rarity.EPIC, "^You found a forgotten Nutcracker laying beneath the ice\\.$", true, true);
        add("Frozen Steve", Rarity.COMMON, "^Frozen Steve fell into the pond long ago, never to resurface\\.\\.\\.until now\\!$", false, true);
        add("Frosty", Rarity.UNCOMMON, "^It\\'s a snowman\\! He looks harmless\\.$", false, true);
        add("Grinch", Rarity.RARE, "^The Grinch stole Jerry\\'s Gifts\\.\\.\\.get them back\\!$", false, true);
        add("Phantom Fisher", Rarity.LEGENDARY, "^The spirit of a long lost Phantom Fisher has come to haunt you\\.$", true, true);
        add("Grim Reaper", Rarity.MYTHIC, "^This can\\'t be\\! The manifestation of death himself\\!$", true, true);
        add("Scarecrow", Rarity.UNCOMMON, "^Phew\\! It\\'s only a Scarecrow\\.$", false, true);
        add("Nightmare", Rarity.RARE, "^You hear trotting from beneath the waves, you caught a Nightmare\\.$", false, true);
        add("Werewolf", Rarity.EPIC, "^It must be a full moon, a Werewolf appears\\.$", false, true);
        add("Jumpin' Jack", Rarity.COMMON, "^Watch out! It\\'s Jumpin\\' Jack\\.$", false, true);
        add("Fried Chicken", Rarity.COMMON, "^Smells of burning\\. Must be a Fried Chicken\\.$", false, true);
        add("Fireproof Witch", Rarity.RARE, "^Trouble\\'s brewing, it\\'s a Fireproof Witch\\!$", false, true);
        add("Magma Slug", Rarity.UNCOMMON, "^From beneath the lava appears a Magma Slug\\.$", false, true);
        add("Moogma", Rarity.UNCOMMON, "^You hear a faint Moo from the lava\\.\\.\\. A Moogma appears\\.$", false, true);
        add("Lava Leech", Rarity.RARE, "^A small but fearsome Lava Leech emerges\\.$", false, true);
        add("Pyroclastic Worm", Rarity.RARE, "^You feel the heat radiating as a Pyroclastic Worm surfaces\\.$", false, true);
        add("Lava Flame", Rarity.RARE, "^A Lava Flame flies out from beneath the lava\\.$", false, true);
        add("Fire Eel", Rarity.RARE, "^A Fire Eel slithers out from the depths\\.$", false, true);
        add("Taurus", Rarity.EPIC, "^Taurus and his steed emerge\\.$", false, true);
        add("Volcanic Snail", Rarity.UNCOMMON, "^You feel a burning sensation as you reel in a Volcanic Snail!$", false, true);
        add("Magma Pillar", Rarity.EPIC, "^A Magma Pillar rises from the lava\\.$", true, true);
        add("Fiery Scuttler", Rarity.LEGENDARY, "^A Fiery Scuttler inconspicuously waddles up to you, friends in tow\\.$", true, true);
        add("Thunder", Rarity.LEGENDARY, "^You hear a massive rumble as Thunder emerges\\.$", true, true);
        add("Lord Jawbus", Rarity.MYTHIC, "^You have angered a legendary creature\\.\\.\\. Lord Jawbus has arrived\\.$", true, true);
        add("Plhlegblast", Rarity.MYTHIC, "^WOAH\\! A Plhlegblast appeared\\.$", true, true);
        add("Ragnarok", Rarity.MYTHIC, "^The sky darkens and the air thickens\\. The end times are upon us: Ragnarok is here\\.$", true, true);
        add("Vanquisher", Rarity.EPIC, "^A Vanquisher is spawning nearby\\!$", true, false);
        add("Oasis Rabbit", Rarity.UNCOMMON, "^An Oasis Rabbit appears from the water\\.$", false, true);
        add("Oasis Sheep", Rarity.UNCOMMON, "^An Oasis Sheep appears from the water\\.$", false, true);
        add("Abyssal Miner", Rarity.LEGENDARY, "^An Abyssal Miner breaks out of the water\\!$", true, true);
        add("Water Worm", Rarity.RARE, "^A Water Worm surfaces\\!$", false, true);
        add("Poisoned Water Worm", Rarity.RARE, "^A Poisoned Water Worm surfaces\\!$", false, true);
        add("Flaming Worm", Rarity.RARE, "^A Flaming Worm surfaces from the depths\\!$", false, true);
        add("Lava Blaze", Rarity.EPIC, "^A Lava Blaze has surfaced from the depths\\!$", false, true);
        add("Lava Pigman", Rarity.EPIC, "^A Lava Pigman arose from the depths\\!$", false, true);
        add("Small Mithril Grubber", Rarity.UNCOMMON, "^A leech of the mines surfaces\\.\\.\\. you\\'ve caught a Mithril Grubber\\.$", false, true);
        add("Medium Mithril Grubber", Rarity.UNCOMMON, "^A leech of the mines surfaces\\.\\.\\. you\\'ve caught a Medium Mithril Grubber\\.$", false, true);
        add("Large Mithril Grubber", Rarity.UNCOMMON, "^A leech of the mines surfaces\\.\\.\\. you\\'ve caught a Large Mithril Grubber\\.$", false, true);
        add("Bloated Mithril Grubber", Rarity.UNCOMMON, "^A leech of the mines surfaces\\.\\.\\. you\\'ve caught a Bloated Mithril Grubber\\.$", false, true);
        add("Trash Gobbler", Rarity.COMMON, "^The Trash Gobbler is hungry for you\\!$", false, true);
        add("Dumpster Diver", Rarity.UNCOMMON, "^A Dumpster Diver has emerged from the swamp\\!$", false, true);
        add("Banshee", Rarity.RARE, "^The desolate wail of a Banshee breaks the silence\\.$", false, true);
        add("Bayou Sludge", Rarity.EPIC, "^A swampy mass of slime emerges, the Bayou Sludge\\!$", false, true);
        add("Alligator", Rarity.LEGENDARY, "^A long snout breaks the surface of the water\\. It\\'s an Alligator\\!$", true, true);
        add("Titanoboa", Rarity.MYTHIC, "^A massive Titanoboa surfaces\\. Its body stretches as far as the eye can see\\.$", true, true);
        add("Nessie", Rarity.MYTHIC, "^You\\'ve caused a disturbance in the loch\\. Could it be\\.\\.\\. Nessie\\?$", true, true);
        add("The Loch Emperor", Rarity.LEGENDARY, "^The Loch Emperor arises from the depths\\.$", true, true);
        add("Bogged", Rarity.COMMON, "^You\\'ve hooked a Bogged\\!$", false, true);
        add("Tadgang", Rarity.UNCOMMON, "^A gang of Liltads\\!$", false, true);
        add("Ent", Rarity.UNCOMMON, "^You\\'ve hooked an Ent, as ancient as the forest itself\\.$", false, true);
        add("Wetwing", Rarity.RARE, "^Look\\! A Wetwing emerges\\!$", false, true);
        add("Stridersurfer", Rarity.RARE, "^You caught a Stridersurfer\\.$", false, true);
        add("Atoll Croaker", Rarity.COMMON, "^An inquisitive Atoll Croaker takes the bait!$", false, true);
        add("Lotus Guardian", Rarity.UNCOMMON, "^A Lotus Guardian emerges, ready to protect the Atoll.$", false, true);
        add("gorF", Rarity.RARE, "^What even is that\\?! A\\.\\.\\. gorF\\?$", false, true);
        add("Drowned Captain", Rarity.EPIC, "^A Drowned Captain takes hold of your bobber!$", false, true);
        add("Puddle Jumper", Rarity.LEGENDARY, "^A Puddle Jumper is preparing for liftoff—cast your rod into it and hold on tight!$", true, false);
        add("Frog Prince", Rarity.MYTHIC, "^Bow down before the Frog Prince\\.\\.\\. or pay the hefty price!$", true, true);
        add("Haggard", Rarity.COMMON, "^A Haggard stumbles to the shore, ready for a fight!$", false, true);
        add("Brineling", Rarity.UNCOMMON, "^A Brineling interrupts you with a stream of bubbles!$", false, true);
        add("Sprawl", Rarity.RARE, "^A Sprawl emerges from the blue, and it's looking for you!$", false, true);
        add("Torrid", Rarity.EPIC, "^The laughter of a Torrid echoes through the air\\.$", false, true);
        add("Silkbreeze", Rarity.LEGENDARY, "^Something zips through the air - it's a Silkbreeze!$", true, true);
        add("Giant Isopod", Rarity.MYTHIC, "^A Giant Isopod was dredged up from the depths!$", true, true);


        drop("PET_ITEM_LUCKY_CLOVER_DROP", RareDropType.LUCKY_CLOVER_CORE, "EPIC", false);
        drop("DEEP_SEA_ORB", RareDropType.DEEP_SEA_ORB, "EPIC", false);
        drop("RADIOACTIVE_VIAL", RareDropType.RADIOACTIVE_VIAL, "MYTHIC", true);
        drop("MAGMA_CORE", RareDropType.MAGMA_CORE, "RARE", false);
        drop("TIKI_MASK", RareDropType.TIKI_MASK, "LEGENDARY", true);
        drop("TITANOBOA_SHED", RareDropType.TITANOBOA_SHED, "LEGENDARY", true);
        drop("SNAKE_EYES", RareDropType.SNAKE_EYES, "LEGENDARY", true);
        drop("OCTOPUS_TENDRIL", RareDropType.OCTOPUS_TENDRIL, "LEGENDARY", false);
        drop("TROUBLED_BUBBLE", RareDropType.TROUBLED_BUBBLE, "LEGENDARY", false);
        drop("SCUTTLER_SHELL", RareDropType.SCUTTLER_SHELL, "MYTHIC", false);
        drop("BURNT_TEXTS", RareDropType.BURNT_TEXTS, "LEGENDARY", true);
        drop("ENCHANTMENT_ULTIMATE_FLASH_1", RareDropType.FLASH_1, "MYTHIC", false, "Flash I");
        drop("ENCHANTMENT_MAGMARIZER_6", RareDropType.MAGMARIZER_6, "RARE", false, "Pyroclasm VI");
        drop("VIBRANT_CORAL", RareDropType.VIBRANT_CORAL, "LEGENDARY", false);
        drop("HILT_OF_TRUE_ICE", RareDropType.TRUE_ICE, "LEGENDARY", false);
        drop("WATER_HYACINTH", RareDropType.WATER_HYACINTH, "LEGENDARY", false);
        drop("DISTANT_ECHO", RareDropType.DISTANT_ECHO, "LEGENDARY", false);
        drop("REINFORCED_NETTING", RareDropType.REINFORCED_NETTING, "LEGENDARY", false);
        drop("PRINCE_CROWN_JEWEL", RareDropType.PRINCES_CROWN_JEWEL, "LEGENDARY", true);
        drop("FLYING_FISH;4", RareDropType.FLYING_FISH_LEGENDARY, "LEGENDARY", false);
        drop("MEGALODON;4", RareDropType.MEGALODON_LEGENDARY, "LEGENDARY", false);
        drop("MEGALODON;3", RareDropType.MEGALODON_EPIC, "EPIC", false);
        drop("SQUID;4", RareDropType.SQUID_LEGENDARY, "LEGENDARY", false);
        drop("SQUID;3", RareDropType.SQUID_EPIC, "EPIC", false);
        drop("SQUID;2", RareDropType.SQUID_RARE, "RARE", false);
        drop("SQUID;1", RareDropType.SQUID_UNCOMMON, "UNCOMMON", false);
        drop("SQUID;0", RareDropType.SQUID_COMMON, "COMMON", false);
        drop("PHOENIX;?", RareDropType.PHOENIX, "SPECIAL", true);
        drop("DYE_CARMINE", RareDropType.CARMINE_DYE, "DARK_RED", true);
        drop("DYE_MIDNIGHT", RareDropType.MIDNIGHT_DYE, "DARK_PURPLE", true);
        drop("DYE_AQUAMARINE", RareDropType.AQUAMARINE_DYE, "AQUA", true);
        drop("DYE_ICEBERG", RareDropType.ICEBERG_DYE, "DARK_AQUA", true);
        drop("DYE_TREASURE", RareDropType.TREASURE_DYE, "GOLD", true);
        drop("DYE_PERIWINKLE", RareDropType.PERIWINKLE_DYE, "DARK_AQUA", true);
        drop("DYE_BONE", RareDropType.BONE_DYE, "WHITE", true);
    }

    public static List<SeaCreature> creatures() {
        return Collections.unmodifiableList(CREATURES);
    }

    /** The sea creature called {@code name} (any case), or null. */
    public static SeaCreature creature(String name) {
        for (SeaCreature creature : CREATURES) if (creature.name().equalsIgnoreCase(name)) return creature;
        return null;
    }

    /** The rare drop called {@code name} (or one of its other names), or null if Feesh doesn't list it. */
    public static RareDrop drop(String name) {
        for (RareDrop drop : DROPS) {
            if (drop.name().equals(name) || drop.alternateNames().contains(name)) return drop;
        }
        return null;
    }

    /** "A" or "An" for {@code word}. */
    public static String article(String word, boolean lowerCase) {
        boolean vowel = !word.isEmpty() && "aeiou".indexOf(Character.toLowerCase(word.charAt(0))) >= 0;
        String article = vowel ? "An" : "A";
        return lowerCase ? article.toLowerCase(Locale.ROOT) : article;
    }

    public enum AlertSource {
        OWN_AND_PARTY("Own + Party"), OWN("Own");

        private final String label;

        AlertSource(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum PriceScope {
        OWN("Own"), OWN_AND_PARTY("Own + Party"), OFF("Off");

        private final String label;

        PriceScope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum DeployableType {
        TOTEM_OF_CORRUPTION("Totem of Corruption"),
        BLACK_HOLE("Black Hole"),
        UMBERELLA("Umberella"),
        FLARE("Flare"),
        DWARVEN_LANTERN("Dwarven Lanterns"),
        FLUX("Flux");

        public final String displayName;

        DeployableType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum AlertableSeaCreature {
        ABYSSAL_MINER("Abyssal Miner", true),
        AGARIMOO("Agarimoo", false),
        ALLIGATOR("Alligator", true),
        ATOLL_CROAKER("Atoll Croaker", false),
        BANSHEE("Banshee", false),
        BAYOU_SLUDGE("Bayou Sludge", false),
        BLUE_RINGED_OCTOPUS("Blue Ringed Octopus", true),
        BLUE_SHARK("Blue Shark", false),
        BLOATED_MITHRIL_GRUBBER("Bloated Mithril Grubber", true),
        BOGGED("Bogged", false),
        BRINELING("Brineling", false),
        CARROT_KING("Carrot King", true),
        CATFISH("Catfish", false),
        DEEP_SEA_PROTECTOR("Deep Sea Protector", false),
        DROWNED_CAPTAIN("Drowned Captain", false),
        DUMPSTER_DIVER("Dumpster Diver", false),
        ENT("Ent", false),
        FIERY_SCUTTLER("Fiery Scuttler", true),
        FIRE_EEL("Fire Eel", false),
        FIREPROOF_WITCH("Fireproof Witch", false),
        FLAMING_WORM("Flaming Worm", false),
        FROG_MAN("Frog Man", false),
        FROG_PRINCE("Frog Prince", true),
        FROZEN_STEVE("Frozen Steve", false),
        FROSTY("Frosty", false),
        GIANT_ISOPOD("Giant Isopod", true),
        GORF("gorF", false),
        HAGGARD("Haggard", false),
        GREAT_WHITE_SHARK("Great White Shark", true),
        GRIM_REAPER("Grim Reaper", true),
        GRINCH("Grinch", false),
        GUARDIAN_DEFENDER("Guardian Defender", false),
        INKLING("Inkling", false),
        JUMPIN_JACK("Jumpin' Jack", false),
        LARGE_MITHRIL_GRUBBER("Large Mithril Grubber", false),
        LAVA_BLAZE("Lava Blaze", false),
        LAVA_FLAME("Lava Flame", false),
        LAVA_LEECH("Lava Leech", false),
        LAVA_PIGMAN("Lava Pigman", false),
        LORD_JAWBUS("Lord Jawbus", true),
        LOTUS_GUARDIAN("Lotus Guardian", false),
        MAGMA_PILLAR("Magma Pillar", false),
        MAGMA_SLUG("Magma Slug", false),
        MANTA_RAY("Manta Ray", false),
        MEDIUM_MITHRIL_GRUBBER("Medium Mithril Grubber", false),
        MOOGMA("Moogma", false),
        NESSIE("Nessie", true),
        NIGHTMARE("Nightmare", false),
        NURSE_SHARK("Nurse Shark", false),
        NUTCRACKER("Nutcracker", true),
        OASIS_RABBIT("Oasis Rabbit", false),
        OASIS_SHEEP("Oasis Sheep", false),
        PHANTOM_FISHER("Phantom Fisher", true),
        PLHLEGBLAST("Plhlegblast", true),
        POISONED_WATER_WORM("Poisoned Water Worm", false),
        PUDDLE_JUMPER("Puddle Jumper", true),
        PYROCLASTIC_WORM("Pyroclastic Worm", false),
        RAGNAROK("Ragnarok", true),
        REINDRAKE("Reindrake", true),
        RIDER_OF_THE_DEEP("Rider of the Deep", false),
        SCARECROW("Scarecrow", false),
        SEA_ARCHER("Sea Archer", false),
        SEA_LEECH("Sea Leech", false),
        SEA_WALKER("Sea Walker", false),
        SEA_WITCH("Sea Witch", false),
        SILKBREEZE("Silkbreeze", true),
        SMALL_MITHRIL_GRUBBER("Small Mithril Grubber", false),
        SPRAWL("Sprawl", false),
        SNAPPING_TURTLE("Snapping Turtle", false),
        SQUID("Squid", false),
        STRIDERSURFER("Stridersurfer", false),
        TADGANG("Tadgang", false),
        TAURUS("Taurus", false),
        THE_LOCH_EMPEROR("The Loch Emperor", true),
        THUNDER("Thunder", true),
        TIGER_SHARK("Tiger Shark", false),
        TITANOBOA("Titanoboa", true),
        TORRID("Torrid", false),
        TRASH_GOBBLER("Trash Gobbler", false),
        VANQUISHER("Vanquisher", true),
        VOLCANIC_SNAIL("Volcanic Snail", false),
        WATER_HYDRA("Water Hydra", true),
        WATER_WORM("Water Worm", false),
        WEREWOLF("Werewolf", false),
        WETWING("Wetwing", false),
        WIKI_TIKI("Wiki Tiki", true),
        YETI("Yeti", true);

        public final String displayName;
        public final boolean enabledByDefault;

        AlertableSeaCreature(String displayName, boolean enabledByDefault) {
            this.displayName = displayName;
            this.enabledByDefault = enabledByDefault;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum HighlightableSeaCreature {
        ABYSSAL_MINER("Abyssal Miner", true),
        AGARIMOO("Agarimoo", false),
        ALLIGATOR("Alligator", true),
        ATOLL_CROAKER("Atoll Croaker", false),
        BANSHEE("Banshee", false),
        BAYOU_SLUDGE("Bayou Sludge", false),
        BLUE_RINGED_OCTOPUS("Blue Ringed Octopus", true),
        BLUE_SHARK("Blue Shark", false),
        BLOATED_MITHRIL_GRUBBER("Bloated Mithril Grubber", true),
        BOGGED("Bogged", false),
        BRINELING("Brineling", false),
        CARROT_KING("Carrot King", true),
        CATFISH("Catfish", false),
        DEEP_SEA_PROTECTOR("Deep Sea Protector", false),
        DROWNED_CAPTAIN("Drowned Captain", false),
        DUMPSTER_DIVER("Dumpster Diver", false),
        ENT("Ent", false),
        FIERY_SCUTTLER("Fiery Scuttler", true),
        FIRE_EEL("Fire Eel", false),
        FIREPROOF_WITCH("Fireproof Witch", false),
        FLAMING_WORM("Flaming Worm", false),
        FLIPFLOPPER("Flipflopper", true),
        FROG_MAN("Frog Man", false),
        FROG_PRINCE("Frog Prince", true),
        FROZEN_STEVE("Frozen Steve", false),
        FROSTY("Frosty", false),
        GIANT_ISOPOD("Giant Isopod", true),
        GORF("gorF", false),
        HAGGARD("Haggard", false),
        GREAT_WHITE_SHARK("Great White Shark", true),
        GRIM_REAPER("Grim Reaper", true),
        GRINCH("Grinch", false),
        GUARDIAN_DEFENDER("Guardian Defender", false),
        INKLING("Inkling", false),
        JAWBUS_FOLLOWER("Jawbus Follower", true),
        JUMPIN_JACK("Jumpin' Jack", false),
        LARGE_MITHRIL_GRUBBER("Large Mithril Grubber", false),
        LAVA_BLAZE("Lava Blaze", false),
        LAVA_FLAME("Lava Flame", false),
        LAVA_LEECH("Lava Leech", false),
        LAVA_PIGMAN("Lava Pigman", false),
        LORD_JAWBUS("Lord Jawbus", true),
        LOTUS_GUARDIAN("Lotus Guardian", false),
        MAGMA_PILLAR("Magma Pillar", false),
        MAGMA_SLUG("Magma Slug", false),
        MANTA_RAY("Manta Ray", false),
        MEDIUM_MITHRIL_GRUBBER("Medium Mithril Grubber", false),
        MOOGMA("Moogma", false),
        NESSIE("Nessie", true),
        NIGHTMARE("Nightmare", false),
        NURSE_SHARK("Nurse Shark", false),
        NUTCRACKER("Nutcracker", true),
        OASIS_RABBIT("Oasis Rabbit", false),
        OASIS_SHEEP("Oasis Sheep", false),
        PHANTOM_FISHER("Phantom Fisher", true),
        PLHLEGBLAST("Plhlegblast", true),
        POISONED_WATER_WORM("Poisoned Water Worm", false),
        PUDDLE_JUMPER("Puddle Jumper", true),
        PYROCLASTIC_WORM("Pyroclastic Worm", false),
        RAGNAROK("Ragnarok", true),
        REINDRAKE("Reindrake", true),
        RIDER_OF_THE_DEEP("Rider of the Deep", false),
        SCARECROW("Scarecrow", false),
        SEA_ARCHER("Sea Archer", false),
        SEA_LEECH("Sea Leech", false),
        SEASHINE("Seashine", true),
        SEA_WALKER("Sea Walker", false),
        SEA_WITCH("Sea Witch", false),
        SILKBREEZE("Silkbreeze", true),
        SMALL_MITHRIL_GRUBBER("Small Mithril Grubber", false),
        SPRAWL("Sprawl", false),
        SNAPPING_TURTLE("Snapping Turtle", false),
        SQUID("Squid", false),
        STRIDERSURFER("Stridersurfer", false),
        TADGANG("Tadgang", false),
        TAURUS("Taurus", false),
        THE_LOCH_EMPEROR("The Loch Emperor", true),
        THUNDER("Thunder", true),
        TIGER_SHARK("Tiger Shark", false),
        TITANOBOA("Titanoboa", true),
        TORRID("Torrid", false),
        TRASH_GOBBLER("Trash Gobbler", false),
        VANQUISHER("Vanquisher", true),
        VOLCANIC_SNAIL("Volcanic Snail", false),
        WATER_HYDRA("Water Hydra", true),
        WATER_WORM("Water Worm", false),
        WEREWOLF("Werewolf", false),
        WETWING("Wetwing", false),
        WIKI_TIKI("Wiki Tiki", true),
        WIKI_TIKI_LASER_TOTEM("Wiki Tiki Laser Totem", true),
        YETI("Yeti", true);

        public final String displayName;
        public final boolean enabledByDefault;

        HighlightableSeaCreature(String displayName, boolean enabledByDefault) {
            this.displayName = displayName;
            this.enabledByDefault = enabledByDefault;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum RareDropType {
        ALL("ALL"),
        LUCKY_CLOVER_CORE("Lucky Clover Core"),
        DEEP_SEA_ORB("Deep Sea Orb"),
        RADIOACTIVE_VIAL("Radioactive Vial"),
        MAGMA_CORE("Magma Core"),
        TIKI_MASK("Tiki Mask"),
        TITANOBOA_SHED("Titanoboa Shed"),
        SNAKE_EYES("Snake Eyes"),
        OCTOPUS_TENDRIL("Octopus Tendril"),
        TROUBLED_BUBBLE("Troubled Bubble"),
        SCUTTLER_SHELL("Scuttler Shell"),
        BURNT_TEXTS("Burnt Texts"),
        FLASH_1("Flash 1"),
        MAGMARIZER_6("Pyroclasm 6"),
        VIBRANT_CORAL("Vibrant Coral"),
        TRUE_ICE("True Ice"),
        PRINCES_CROWN_JEWEL("Prince's Crown Jewel"),
        DISTANT_ECHO("Distant Echo"),
        REINFORCED_NETTING("Reinforced Netting"),
        WATER_HYACINTH("Water Hyacinth"),
        MEGALODON_LEGENDARY("Megalodon (Legendary)"),
        MEGALODON_EPIC("Megalodon (Epic)"),
        FLYING_FISH_LEGENDARY("Flying Fish (Legendary)"),
        SQUID_LEGENDARY("Squid (Legendary)"),
        SQUID_EPIC("Squid (Epic)"),
        SQUID_RARE("Squid (Rare)"),
        SQUID_UNCOMMON("Squid (Uncommon)"),
        SQUID_COMMON("Squid (Common)"),
        PHOENIX("Phoenix"),
        CARMINE_DYE("Carmine Dye"),
        AQUAMARINE_DYE("Aquamarine Dye"),
        ICEBERG_DYE("Iceberg Dye"),
        MIDNIGHT_DYE("Midnight Dye"),
        TREASURE_DYE("Treasure Dye"),
        PERIWINKLE_DYE("Periwinkle Dye"),
        BONE_DYE("Bone Dye");

        public final String displayName;

        RareDropType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
