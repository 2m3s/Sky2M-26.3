package com.epic60869.sky2m.features.dungeons.bridge;

import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Source;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One fact this client worked out itself, in the shape Killer560's Mod's bridge adapters read (its {@code dg.v1.*}
 * payloads): what kind of fact ({@code key}), which one ({@code id}, for "already sent" bookkeeping) and its fields.
 * Built from {@link PartyDungeonState}'s own (SELF) facts, so the bridge never works anything out again.
 */
public record SelfFact(String key, String id, JsonObject payload) {
    public static final String KEY_FLAG = "dg.v1.flag";
    public static final String KEY_COUNTER = "dg.v1.counter";
    public static final String KEY_ROOM_SECRETS = "dg.v1.roomSecrets";
    public static final String KEY_ROOM = "dg.v1.room";
    public static final String KEY_DOOR = "dg.v1.door";
    public static final String KEY_DRAGON = "dg.v1.dragon";
    public static final String KEY_MELODY = "dg.v1.melody";

    /** Everything this client knows itself right now. */
    static List<SelfFact> snapshot() {
        List<SelfFact> out = new ArrayList<>();
        for (PartyDungeonState.Flag flag : PartyDungeonState.Flag.values()) {
            PartyDungeonState.Fact<Boolean> f = PartyDungeonState.flagFact(flag);
            if (f == null || f.source() != Source.SELF) continue;
            JsonObject o = new JsonObject();
            o.addProperty("flag", flag.wire);
            out.add(new SelfFact(KEY_FLAG, "flag:" + flag.wire, o));
        }
        for (PartyDungeonState.Counter counter : PartyDungeonState.Counter.values()) {
            PartyDungeonState.Fact<Integer> f = PartyDungeonState.counter(counter);
            if (f == null || f.source() != Source.SELF) continue;
            JsonObject o = new JsonObject();
            o.addProperty("counter", counter.wire);
            o.addProperty("value", f.value());
            out.add(new SelfFact(KEY_COUNTER, "counter:" + counter.wire, o));
        }
        for (Map.Entry<String, PartyDungeonState.Fact<PartyDungeonState.RoomSecrets>> e : PartyDungeonState.rooms().entrySet()) {
            if (e.getValue().source() != Source.SELF) continue;
            JsonObject o = new JsonObject();
            o.addProperty("room", e.getKey());
            o.addProperty("found", e.getValue().value().found());
            o.addProperty("total", e.getValue().value().total());
            out.add(new SelfFact(KEY_ROOM_SECRETS, "roomSecrets:" + e.getKey(), o));
        }
        for (com.epic60869.sky2m.features.dungeons.WitherDragons.DragonEvent d : com.epic60869.sky2m.features.dungeons.WitherDragons.dragonEvents()) {
            JsonObject o = new JsonObject();
            o.addProperty("colour", d.colour());
            o.addProperty("event", d.alive() ? "alive" : "dead");
            out.add(new SelfFact(KEY_DRAGON, "dragon:" + d.colour() + ":" + (d.alive() ? "alive" : "dead") + ":" + d.count(), o));
        }
        for (com.epic60869.sky2m.features.dungeons.DoorHighlight.MapDoor door : com.epic60869.sky2m.features.dungeons.DoorHighlight.mapDoors()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", door.x());
            o.addProperty("z", door.z());
            o.addProperty("col", door.col());
            o.addProperty("row", door.row());
            o.addProperty("type", door.blood() ? "blood" : "wither");
            out.add(new SelfFact(KEY_DOOR, "door:" + door.col() + "," + door.row(), o));
        }
        return out;
    }
}
