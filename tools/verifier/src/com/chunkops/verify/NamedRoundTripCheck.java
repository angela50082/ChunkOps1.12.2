package com.chunkops.verify;

import com.chunkops.core.Mcops;
import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 名称化往返保真检查（GUI 复制粘贴实际链路）：
 * chunk 源 → decode → export(.mcops 名称化) → import(活注册表反解, jeid=true) → decode → 逐状态对比。
 * 用法: java -cp out com.chunkops.verify.NamedRoundTripCheck <worldDir> <registry-snapshot.json> [region 过滤]
 */
public class NamedRoundTripCheck {

    public static void main(String[] args) throws Exception {
        File worldDir = new File(args[0]);
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[1]));
        String filter = args.length > 2 ? args[2] : null;
        File regDir = new File(worldDir, "region");
        long chunks = 0, badChunks = 0, totalMismatch = 0;
        Map<String, Long> mismatchBlocks = new TreeMap<String, Long>();
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
                // 源 stateIds（分 section 存）
                List<int[]> srcSecs = new ArrayList<int[]>();
                List<NbtNode> secs = SectionCodec.sectionsOf(level);
                boolean hasMod = false;
                for (NbtNode sec : secs) {
                    int[] ids = SectionCodec.decode(sec);
                    if (ids == null) continue;
                    srcSecs.add(ids);
                    for (int s : ids) {
                        if (s == 0) continue;
                        String n = snap.lookupBlockName(s >> 4);
                        if (n != null && (n.startsWith("enderio:") || n.startsWith("forestry:"))) hasMod = true;
                    }
                }
                if (!hasMod) continue;
                // 名称化往返
                List<NbtNode> roots = new ArrayList<NbtNode>();
                roots.add(root);
                byte[] blob = Mcops.exportChunks(roots, snap, "", "check");
                Mcops.ImportReport report = new Mcops.ImportReport();
                List<NbtNode> imported = Mcops.importChunks(blob, snap, report, true);
                NbtNode level2 = imported.get(0).get("Level");
                int secIdx = 0;
                long chunkBad = 0;
                for (NbtNode sec2 : SectionCodec.sectionsOf(level2)) {
                    int[] ids2 = SectionCodec.decode(sec2);
                    if (ids2 == null) continue;
                    if (secIdx < srcSecs.size()) {
                        int[] src = srcSecs.get(secIdx);
                        for (int k = 0; k < src.length; k++) {
                            if (src[k] != ids2[k]) {
                                chunkBad++;
                                int id = src[k] >> 4;
                                String n = snap.lookupBlockName(id);
                                mismatchBlocks.put((n == null ? "unknown:" + id : n) + "#" + (src[k] & 15),
                                        Long.valueOf(1));
                            }
                        }
                    }
                    secIdx++;
                }
                if (chunkBad > 0) {
                    badChunks++;
                    totalMismatch += chunkBad;
                }
            }
        }
        System.out.println("检查 chunk=" + chunks + " 不一致 chunk=" + badChunks
                + " 状态不一致=" + totalMismatch);
        for (Map.Entry<String, Long> e : mismatchBlocks.entrySet()) {
            System.out.println("  ↺ " + e.getKey());
        }
    }
}
