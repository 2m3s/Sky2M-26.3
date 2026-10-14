package com.epic60869.sky2m.features.profileviewer.networth;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The Hypixel API's gzipped NBT as plain Java values, like prismarine-nbt's {@code simplify} that SkyHelper-Networth
 * reads: compounds are {@code LinkedHashMap}s (in file order), lists are {@code List}s, numbers are boxed, and byte,
 * int and long arrays stay arrays. No Minecraft classes, so the networth port can be tested on its own.
 */
public final class Nbt {
    private Nbt() {}

    /** The root compound of base64 gzipped NBT, or an empty map when it can't be read. */
    public static Map<String, Object> decode(String base64) {
        if (base64 == null || base64.isEmpty()) return new LinkedHashMap<>();
        try {
            return decode(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The root compound of gzipped NBT bytes, or an empty map when it can't be read. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] gzipped) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)))) {
            int type = in.readUnsignedByte();
            if (type != 10) return new LinkedHashMap<>();
            in.readUTF();
            return (Map<String, Object>) read(in, type);
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The {@code i} item list of base64 inventory data ({@code inventory.inv_contents.data} and the like). */
    @SuppressWarnings("unchecked")
    public static List<Object> items(String base64) {
        Object list = decode(base64).get("i");
        return list instanceof List ? (List<Object>) list : new ArrayList<>();
    }

    private static Object read(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> {
                byte[] bytes = new byte[in.readUnsignedShort()];
                in.readFully(bytes);
                yield modifiedUtf8(bytes);
            }
            case 9 -> {
                int element = in.readUnsignedByte();
                int size = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, size));
                for (int i = 0; i < size; i++) list.add(read(in, element));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String key = in.readUTF();
                    map.put(key, read(in, t));
                }
                yield map;
            }
            case 11 -> {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                yield ints;
            }
            case 12 -> {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                yield longs;
            }
            default -> throw new IOException("unknown NBT tag " + type);
        };
    }

    /** NBT strings are Java's modified UTF-8; ordinary UTF-8 reads them the same apart from rare characters. */
    private static String modifiedUtf8(byte[] bytes) {
        try {
            byte[] withLength = new byte[bytes.length + 2];
            withLength[0] = (byte) (bytes.length >> 8);
            withLength[1] = (byte) bytes.length;
            System.arraycopy(bytes, 0, withLength, 2, bytes.length);
            return new DataInputStream(new ByteArrayInputStream(withLength)).readUTF();
        } catch (IOException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
