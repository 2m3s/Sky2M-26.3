import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Re-encodes Skyblocker dungeon .skeleton files (zlib + Java-serialized sorted int[]) as
 * "S2MK" + deflate(level 9) of varint count + zigzag first value + varint deltas.
 * Run after copying new skeletons from Skyblocker: java tools/SkelPack.java src/main/resources/assets/sky2m/dungeons --write
 * (without --write it only reports the sizes). Already-packed files are skipped; DungeonManager.readRoom reads both forms.
 */
public class SkelPack {
    static final byte[] MAGIC = {'S', '2', 'M', 'K'};

    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        boolean write = a.length > 1 && a[1].equals("--write");
        long before = 0, after = 0;
        List<Path> files;
        try (var s = Files.walk(root)) { files = s.filter(p -> p.toString().endsWith(".skeleton")).sorted().toList(); }
        for (Path p : files) {
            byte[] raw = Files.readAllBytes(p);
            if (raw.length >= 4 && Arrays.equals(Arrays.copyOf(raw, 4), MAGIC)) { System.out.println("already packed " + p); continue; }
            int[] data;
            try (ObjectInputStream in = new ObjectInputStream(new InflaterInputStream(new ByteArrayInputStream(raw)))) { data = (int[]) in.readObject(); }
            for (int i = 1; i < data.length; i++) if (data[i] < data[i - 1]) throw new IllegalStateException("not sorted: " + p);
            byte[] packed = encode(data);
            int[] back = decode(packed);
            if (!Arrays.equals(data, back)) throw new IllegalStateException("roundtrip failed: " + p);
            before += raw.length; after += packed.length;
            if (write) Files.write(p, packed);
        }
        System.out.printf("%d files: %d -> %d bytes (%.1f%%)%n", files.size(), before, after, 100.0 * after / Math.max(1, before));
    }

    static byte[] encode(int[] d) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varint(body, d.length);
        long prev = 0;
        for (int i = 0; i < d.length; i++) {
            long v = i == 0 ? ((long) d[0] << 1) ^ ((long) d[0] >> 63) : (long) d[i] - prev;
            varint(body, v);
            prev = d[i];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
        try (DeflaterOutputStream z = new DeflaterOutputStream(out, def)) { body.writeTo(z); }
        return out.toByteArray();
    }

    static void varint(OutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    // Mirror of the decoder in DungeonManager, used for the roundtrip check.
    static int[] decode(byte[] b) throws IOException {
        try (InputStream in = new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(b, 4, b.length - 4), new Inflater(true)))) {
            int n = (int) readVarint(in);
            int[] d = new int[n];
            long prev = 0;
            for (int i = 0; i < n; i++) {
                long v = readVarint(in);
                prev = i == 0 ? (v >>> 1) ^ -(v & 1) : prev + v;
                d[i] = (int) prev;
            }
            return d;
        }
    }

    static long readVarint(InputStream in) throws IOException {
        long r = 0; int shift = 0, b;
        do { b = in.read(); if (b < 0) throw new EOFException(); r |= (long) (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
        return r;
    }
}
