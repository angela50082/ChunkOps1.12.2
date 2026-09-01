package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;

/**
 * JEID 世界修复：把 legacy section（无 Palette int[]）就地转换为 JEID palette 格式。
 * 崩溃背景：JEID reid mixin 读取时把 Blocks 一律按 palette 索引解释，无 legacy 分支。
 * 流程：每 chunk：解码全部 legacy section 的 stateIds → encodeSection(jeid=true) → RegionWriter 重写。
 * 用法: java -cp out com.chunkops.verify.FixJeidWorld <worldDir>
 */
public class FixJeidWorld {

    public static void main(String[] args) throws Exception {
        File worldDir = new File(args[0]);
        File regDir = new File(worldDir, "region");
        int fixedChunks = 0, bad = 0;
        for (File region : regDir.listFiles()) {
            if (!region.getName().endsWith(".mca")) continue;
            RegionReader rr = new RegionReader(region);
            boolean dirty = false;
            RegionWriter rw = new RegionWriter(region);
            for (int i = 0; i < 1024; i++) {
                byte[] payload = rr.readChunkData(i);
                if (payload == null) continue;
                NbtNode root = RegionWriter.unpackChunk(payload);
                NbtNode level = root.get("Level");
                if (level == null) continue;
                boolean chunkDirty = false;
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    NbtNode pal = sec.get("Palette");
                    if (pal != null && pal.value instanceof int[]) continue; // 已是 JEID
                    int[] ids = SectionCodec.decode(sec);
                    if (ids == null) continue;
                    NbtNode rep = SectionCodec.encodeSection(ids, true);
                    NbtNode y = sec.get("Y");
                    if (y != null) rep.asMap().put("Y", y);
                    for (String light : new String[]{"SkyLight", "BlockLight"}) {
                        NbtNode l = sec.get(light);
                        if (l != null) rep.asMap().put(light, l);
                    }
                    sec.asMap().clear();
                    sec.asMap().putAll(rep.asMap());
                    chunkDirty = true;
                }
                if (chunkDirty) {
                    rw.setChunk(i, RegionWriter.packChunk(root));
                    dirty = true;
                    fixedChunks++;
                }
            }
            if (dirty) {
                rw.write();
                System.out.println("region " + region.getName() + " 已修复并写回");
            }
        }
        System.out.println("修复 chunk=" + fixedChunks + " 错误=" + bad);
    }
}
