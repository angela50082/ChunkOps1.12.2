package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 两个 region 文件逐 chunk 对比（粘贴排障）：
 * 打印每个差异 chunk 的两侧方块分布 top5（经注册表快照名称化）。
 * 用法: java -cp out com.chunkops.verify.DiffRegions <old.mca> <new.mca> <registry-snapshot.json>
 */
public class DiffRegions {

    public static void main(String[] args) throws Exception {
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[2]));
        RegionReader rrOld = new RegionReader(new File(args[0]));
        RegionReader rrNew = new RegionReader(new File(args[1]));
        int diffs = 0;
        for (int i = 0; i < 1024; i++) {
            byte[] p1 = rrOld.readChunkData(i);
            byte[] p2 = rrNew.readChunkData(i);
            if (p1 == null && p2 == null) continue;
            if (java.util.Arrays.equals(p1, p2)) continue;
            diffs++;
            int cx = (i & 31);
            int cz = (i >> 5) & 31;
            System.out.println("== 差异 chunk (" + cx + "," + cz + ") ==");
            if (p1 != null) printTop("  [粘贴前]", p1, snap);
            if (p2 != null) printTop("  [现在  ]", p2, snap);
        }
        System.out.println("差异 chunk 数=" + diffs);
    }

    static void printTop(String tag, byte[] payload, RegistrySnapshot snap) throws Exception {
        NbtNode root = RegionWriter.unpackChunk(payload);
        NbtNode level = root.get("Level");
        Map<Integer, Long> counts = new TreeMap<Integer, Long>();
        for (NbtNode sec : SectionCodec.sectionsOf(level)) {
            int[] ids = SectionCodec.decode(sec);
            if (ids == null) continue;
            for (int s : ids) {
                if (s == 0) continue;
                Long c = counts.get(s);
                counts.put(s, c == null ? 1 : c + 1);
            }
        }
        java.util.List<Map.Entry<Integer, Long>> list =
                new java.util.ArrayList<Map.Entry<Integer, Long>>(counts.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<Integer, Long>>() {
            public int compare(Map.Entry<Integer, Long> a, Map.Entry<Integer, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        StringBuilder sb = new StringBuilder(tag);
        int n = 0;
        for (Map.Entry<Integer, Long> e : list) {
            if (n++ >= 5) break;
            int id = e.getKey() >> 4;
            String name = snap.lookupBlockName(id);
            sb.append(" ").append(name == null ? "unknown:" + id : name).append("#")
                    .append(e.getKey() & 15).append("x").append(e.getValue());
        }
        System.out.println(sb);
    }
}
