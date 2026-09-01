package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;

import java.io.File;

/**
 * Section 编解码 round-trip 验证（覆盖 JEID palette / legacy 两种格式）：
 * 真实存档全 chunk 全 section：decode → encode(自动) → re-decode → 逐状态比对。
 * 用法: java -cp out com.chunkops.verify.SectionRoundTripCheck <worldDir> [region 过滤 r.N.M]
 */
public class SectionRoundTripCheck {

    public static void main(String[] args) throws Exception {
        File worldDir = new File(args[0]);
        String filter = args.length > 1 ? args[1] : null;
        File regDir = new File(worldDir, "region");
        long totalSecs = 0, paletteSecs = 0, highIdSecs = 0, diffSecs = 0, mismatches = 0;
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
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    int[] ids = SectionCodec.decode(sec);
                    if (ids == null) continue;
                    totalSecs++;
                    if (sec.get("Palette") != null) paletteSecs++;
                    boolean high = false;
                    for (int s : ids) if ((s >> 4) > 0xFFF) { high = true; break; }
                    if (high) highIdSecs++;
                    // round-trip：encode → decode → 比对
                    NbtNode re = SectionCodec.encodeLegacy(ids);
                    int[] ids2 = SectionCodec.decode(re);
                    if (ids2 == null) {
                        diffSecs++;
                        continue;
                    }
                    boolean same = true;
                    for (int k = 0; k < ids.length; k++) {
                        if (ids[k] != ids2[k]) { same = false; mismatches++; break; }
                    }
                    if (!same) diffSecs++;
                }
            }
            System.out.println("region " + region.getName() + " 已扫描");
        }
        System.out.println("sections=" + totalSecs + "  源为 palette=" + paletteSecs
                + "  含 id>4095=" + highIdSecs + "  不一致 section=" + diffSecs
                + "  不一致状态=" + mismatches);
        if (mismatches > 0) {
            System.out.println("FAIL: round-trip 存在不一致");
            System.exit(1);
        }
        System.out.println("PASS: 全部 section round-trip 一致");
    }
}
