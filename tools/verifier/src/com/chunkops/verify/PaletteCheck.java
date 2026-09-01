package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;

/**
 * JEID palette section 合法性普查（崩溃定位）：
 * 找出 Palette int[] 长度 < Blocks 最大索引+1 的 section（游戏读会越界崩溃）。
 * 用法: java -cp out com.chunkops.verify.PaletteCheck <worldDir> [region 过滤]
 */
public class PaletteCheck {

    public static void main(String[] args) throws Exception {
        File worldDir = new File(args[0]);
        String filter = args.length > 1 ? args[1] : null;
        File regDir = new File(worldDir, "region");
        int bad = 0, palSecs = 0, legacySecs = 0;
        for (File region : regDir.listFiles()) {
            if (!region.getName().endsWith(".mca")) continue;
            if (filter != null && !region.getName().contains(filter)) continue;
            RegionReader rr = new RegionReader(region);
            for (int i = 0; i < 1024; i++) {
                byte[] payload = rr.readChunkData(i);
                if (payload == null) continue;
                NbtNode root = RegionWriter.unpackChunk(payload);
                NbtNode level = root.get("Level");
                if (level == null) continue;
                int cx = ((Number) level.get("xPos").value).intValue();
                int cz = ((Number) level.get("zPos").value).intValue();
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    NbtNode pal = sec.get("Palette");
                    NbtNode blocks = sec.get("Blocks");
                    if (pal == null) {
                        legacySecs++;
                        continue;
                    }
                    palSecs++;
                    if (blocks == null || blocks.type != NbtNode.TAG_BYTE_ARRAY) continue;
                    int[] palVals = pal.value instanceof int[] ? (int[]) pal.value : null;
                    if (palVals == null) continue;
                    byte[] b = (byte[]) blocks.value;
                    int maxIdx = 0;
                    for (int k = 0; k < b.length; k++) {
                        int v = b[k] & 0xFF;
                        if (v > maxIdx) maxIdx = v;
                    }
                    if (maxIdx >= palVals.length) {
                        bad++;
                        System.out.println("BAD chunk(" + cx + "," + cz + ") Y=" + SectionCodec.sectionY(sec)
                                + " paletteLen=" + palVals.length + " maxIdx=" + maxIdx);
                        StringBuilder sb = new StringBuilder("  palette:");
                        for (int k = 0; k < Math.min(10, palVals.length); k++) {
                            sb.append(" ").append(palVals[k]);
                        }
                        System.out.println(sb);
                    }
                }
            }
        }
        System.out.println("palette sections=" + palSecs + " legacy sections=" + legacySecs + " 越界 section=" + bad);
    }
}
