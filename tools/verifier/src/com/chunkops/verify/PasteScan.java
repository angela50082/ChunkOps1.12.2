package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 全存档 section 普查（P2 粘贴排障）：
 * 每个 chunk 打印方块多样性（种类数/top1 占比/top1 名称/unknown 数），定位"种类单一"异常。
 * 用法: java -cp out com.chunkops.verify.PasteScan <worldDir> <registry-snapshot.json> [region 过滤] [chunk 过滤 cx1,cz1,cx2,cz2]
 */
public class PasteScan {

    public static void main(String[] args) throws Exception {
        File worldDir = new File(args[0]);
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[1]));
        String filter = args.length > 2 ? args[2] : null;
        int[] range = null;
        if (args.length > 3) {
            String[] p = args[3].split(",");
            range = new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        }
        File regDir = new File(worldDir, "region");
        int legacySecs = 0, paletteSecs = 0;
        int chunks = 0;
        for (File region : regDir.listFiles()) {
            if (!region.getName().endsWith(".mca")) continue;
            if (filter != null && !region.getName().contains(filter)) continue;
            RegionReader rr = new RegionReader(region);
            for (int i = 0; i < 1024; i++) {
                byte[] payload = rr.readChunkData(i);
                if (payload == null) continue;
                chunks++;
                NbtNode root = RegionWriter.unpackChunk(payload);
                NbtNode level = root.get("Level");
                if (level == null) continue;
                int cx = ((Number) level.get("xPos").value).intValue();
                int cz = ((Number) level.get("zPos").value).intValue();
                if (range != null && (cx < range[0] || cx > range[2] || cz < range[1] || cz > range[3])) continue;
                Map<Integer, Long> counts = new TreeMap<Integer, Long>();
                boolean chunkLegacy = false;
                long unknownCount = 0;
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    if (sec.get("Palette") != null) paletteSecs++;
                    else { legacySecs++; chunkLegacy = true; }
                    int[] ids = SectionCodec.decode(sec);
                    if (ids == null) continue;
                    for (int s : ids) {
                        if (s == 0) continue;
                        Long c = counts.get(s);
                        counts.put(s, c == null ? 1 : c + 1);
                        if (snap.lookupBlockName(s >> 4) == null) unknownCount++;
                    }
                }
                long total = 0, top1 = 0;
                int topId = 0;
                for (Map.Entry<Integer, Long> e : counts.entrySet()) {
                    total += e.getValue();
                    if (e.getValue() > top1) { top1 = e.getValue(); topId = e.getKey(); }
                }
                String flag = "";
                if (chunkLegacy) flag += " [LEGACY]";
                if (unknownCount > 0) flag += " [unknown x" + unknownCount + "]";
                if (total > 0) {
                    double ratio = top1 / (double) total;
                    if (ratio > 0.55 || unknownCount > 0 || chunkLegacy) flag += " ★";
                    System.out.println(String.format("chunk (%d,%d) 方块=%d 种类=%d top1=%s %.0f%%%s",
                            cx, cz, total, counts.size(),
                            snap.lookupBlockName(topId >> 4) == null ? "unknown:" + (topId >> 4)
                                    : snap.lookupBlockName(topId >> 4) + "#" + (topId & 15),
                            ratio * 100, flag));
                }
            }
        }
        System.out.println("chunks=" + chunks + "  palette sections=" + paletteSecs
                + "  legacy sections=" + legacySecs);
    }
}
