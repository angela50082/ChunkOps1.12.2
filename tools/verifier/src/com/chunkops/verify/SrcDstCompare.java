package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 源/目标区块并排对比：名称+数量（粘贴变体错位诊断）。
 * 用法: java -cp out com.chunkops.verify.SrcDstCompare <srcWorld> <srcRegion.mca> <srcChunkIdx>
 *      <dstWorld> <dstRegion.mca> <dstChunkIdx> <registry-snapshot.json>
 */
public class SrcDstCompare {

    public static void main(String[] args) throws Exception {
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[6]));
        Map<Integer, Long> src = dump(new File(args[1]), Integer.parseInt(args[2]));
        Map<Integer, Long> dst = dump(new File(args[4]), Integer.parseInt(args[5]));
        System.out.println("=== 源区（" + args[0] + " " + args[1] + " #" + args[2] + "）===");
        print(src, snap);
        System.out.println("=== 目标区（" + args[3] + " " + args[4] + " #" + args[5] + "）===");
        print(dst, snap);
        // 差集
        System.out.println("=== 仅源有（数量差>0）===");
        for (Map.Entry<Integer, Long> e : src.entrySet()) {
            long d = e.getValue() - (dst.containsKey(e.getKey()) ? dst.get(e.getKey()) : 0L);
            if (d != 0) {
                System.out.println("  " + name(e.getKey(), snap) + "#" + (e.getKey() & 15)
                        + " stateId=" + e.getKey() + " 差=" + d);
            }
        }
        System.out.println("=== 仅目标有 ===");
        for (Map.Entry<Integer, Long> e : dst.entrySet()) {
            long d = e.getValue() - (src.containsKey(e.getKey()) ? src.get(e.getKey()) : 0L);
            if (d != 0) {
                System.out.println("  " + name(e.getKey(), snap) + "#" + (e.getKey() & 15)
                        + " stateId=" + e.getKey() + " 差=" + d);
            }
        }
    }

    static Map<Integer, Long> dump(File region, int idx) throws Exception {
        RegionReader rr = new RegionReader(region);
        byte[] payload = rr.readChunkData(idx);
        Map<Integer, Long> counts = new TreeMap<Integer, Long>();
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

    static String name(int stateId, RegistrySnapshot snap) {
        String n = snap.lookupBlockName(stateId >> 4);
        return n == null ? "unknown:" + (stateId >> 4) : n;
    }

    static void print(Map<Integer, Long> counts, RegistrySnapshot snap) {
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            System.out.println("  " + name(e.getKey(), snap) + "#" + (e.getKey() & 15)
                    + " stateId=" + e.getKey() + " x" + e.getValue());
        }
    }
}
