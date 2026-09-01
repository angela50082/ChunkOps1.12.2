package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;

/**
 * JEID 编码验证：真实存档 chunk → decode → encodeSection(jeid=true) → 越界检查 + round-trip。
 * 用法: java -cp out com.chunkops.verify.JeidEncodeCheck <worldDir>
 */
public class JeidEncodeCheck {

    public static void main(String[] args) throws Exception {
        File regDir = new File(args[0], "region");
        long secs = 0, bad = 0, mismatch = 0;
        for (File region : regDir.listFiles()) {
            if (!region.getName().endsWith(".mca")) continue;
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
                    secs++;
                    NbtNode enc = SectionCodec.encodeSection(ids, true); // JEID palette
                    NbtNode pal = enc.get("Palette");
                    NbtNode blocks = enc.get("Blocks");
                    if (pal == null || blocks == null
                            || !(pal.value instanceof int[]) || blocks.type != NbtNode.TAG_BYTE_ARRAY) {
                        bad++;
                        continue;
                    }
                    int[] pv = (int[]) pal.value;
                    byte[] b = (byte[]) blocks.value;
                    int maxIdx = 0;
                    for (byte bb : b) maxIdx = Math.max(maxIdx, bb & 0xFF);
                    if (maxIdx >= pv.length) {
                        bad++;
                        System.out.println("BAD paletteLen=" + pv.length + " maxIdx=" + maxIdx);
                        continue;
                    }
                    int[] back = SectionCodec.decode(enc);
                    if (back == null) { bad++; continue; }
                    for (int k = 0; k < ids.length; k++) {
                        if (ids[k] != back[k]) { mismatch++; break; }
                    }
                }
            }
        }
        System.out.println("sections=" + secs + " 越界/格式坏=" + bad + " round-trip 不一致=" + mismatch);
        if (bad > 0 || mismatch > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS: JEID 编码正确（无越界，round-trip 一致）");
    }
}
