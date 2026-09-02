package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 3x3 源/目标逐 chunk 对比（粘贴变体错位诊断）：只打印含 forestry/enderio 的差异。
 * 用法: java -cp out com.chunkops.verify.LoopCompare <srcRegion.mca> <dstRegion.mca> <srcX0> <srcZ0> <dstX0> <dstZ0> <snap.json>
 */
public class LoopCompare {

    public static void main(String[] args) throws Exception {
        File srcReg = new File(args[0]);
        File dstReg = new File(args[1]);
        int sx = Integer.parseInt(args[2]), sz = Integer.parseInt(args[3]);
        int dx = Integer.parseInt(args[4]), dz = Integer.parseInt(args[5]);
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[6]));
        for (int dz2 = 0; dz2 < 3; dz2++) {
            for (int dx2 = 0; dx2 < 3; dx2++) {
                int scx = sx + dx2, scz = sz + dz2;
                int dcx = dx + dx2, dcz = dz + dz2;
                Map<Integer, Long> src = dump(srcReg, (scz & 31) * 32 + (scx & 31));
                Map<Integer, Long> dst = dump(dstReg, (dcz & 31) * 32 + (dcx & 31));
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<Integer, Long> e : src.entrySet()) {
                    String n = snap.lookupBlockName(e.getKey() >> 4);
                    if (n == null || (!n.contains("forestry") && !n.contains("ender"))) continue;
                    long d = e.getValue() - (dst.containsKey(e.getKey()) ? dst.get(e.getKey()) : 0L);
                    if (d != 0 || (dst.keySet().contains(e.getKey()))) { }
                }
                // 直接逐 stateId 对比并打印 forestry/ender 差异
                TreeMap<Integer, Long> diff = new TreeMap<Integer, Long>();
                TreeMap<Integer, Long> all = new TreeMap<Integer, Long>();
                for (Map.Entry<Integer, Long> e : src.entrySet()) {
                    long d = e.getValue() - (dst.containsKey(e.getKey()) ? dst.get(e.getKey()) : 0L);
                    if (d != 0) diff.put(e.getKey(), d);
                }
                for (Map.Entry<Integer, Long> e : dst.entrySet()) {
                    long d = e.getValue() - (src.containsKey(e.getKey()) ? src.get(e.getKey()) : 0L);
                    if (d != 0) diff.put(e.getKey(), d);
                }
                for (Map.Entry<Integer, Long> e : diff.entrySet()) {
                    String n = snap.lookupBlockName(e.getKey() >> 4);
                    if (n != null && (n.contains("forestry") || n.contains("ender"))) {
                        System.out.println("chunk 源(" + scx + "," + scz + ")->目标(" + dcx + "," + dcz + ")"
                                + " " + n + "#" + (e.getKey() & 15) + " stateId=" + e.getKey() + " 差=" + e.getValue());
                    }
                }
            }
        }
    }

    static Map<Integer, Long> dump(File region, int idx) throws Exception {
        Map<Integer, Long> counts = new TreeMap<Integer, Long>();
        RegionReader rr = new RegionReader(region);
        byte[] payload = rr.readChunkData(idx);
        if (payload == null) return counts;
        NbtNode root = RegionWriter.unpackChunk(payload);
        NbtNode level = root.get("Level");
        if (level == null) return counts;
        for (NbtNode sec : SectionCodec.sectionsOf(level)) {
            int[] ids = SectionCodec.decode(sec);
            if (ids == null) continue;
            for (int s : ids) {
                if (s == 0) continue;
                Long c = counts.get(s);
                counts.put(s, c == null ? 1 : c + 1);
            }
        }
        return counts;
    }
}
