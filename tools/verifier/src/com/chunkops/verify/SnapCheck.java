package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用快照把指定 chunk 的每个 palette 项解码成名称，核对快照与存盘是否一致。
 * 用法: java -cp out com.chunkops.verify.SnapCheck <region.mca> <registry-snapshot.json> <chunkIdx...>
 */
public class SnapCheck {
    public static void main(String[] a) throws Exception {
        RegionReader rr = new RegionReader(new File(a[0]));
        RegistrySnapshot snap = RegistrySnapshot.load(new File(a[1]));
        // 无 chunk 索引参数 → 扫描全部区块（只报含 unknown 的区块）
        if (a.length == 2) {
            int unkChunks = 0;
            for (int i = 0; i < 1024; i++) {
                byte[] p = rr.readChunkData(i);
                if (p == null) continue;
                NbtNode root = RegionWriter.unpackChunk(p);
                NbtNode level = root.get("Level");
                if (level == null) continue;
                TreeMap<Integer, Integer> c = new TreeMap<Integer, Integer>();
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    int[] ids = SectionCodec.decode(sec);
                    if (ids == null) continue;
                    for (int s : ids) if (s != 0) c.put(s, c.containsKey(s) ? c.get(s) + 1 : 1);
                }
                java.util.List<String> unk = new java.util.ArrayList<String>();
                for (Map.Entry<Integer, Integer> e : c.entrySet()) {
                    if (snap.lookupBlockName(e.getKey()) == null) unk.add(e.getKey() + "x" + e.getValue());
                }
                if (!unk.isEmpty()) {
                    unkChunks++;
                    int cx = ((Number) level.get("xPos").value).intValue();
                    int cz = ((Number) level.get("zPos").value).intValue();
                    System.out.println("chunk (" + cx + "," + cz + ") unknown: " + String.join(" ", unk));
                }
            }
            System.out.println("含 unknown 的区块数=" + unkChunks);
            return;
        }
        for (int i = 2; i < a.length; i++) {
            int idx = Integer.parseInt(a[i]);
            byte[] p = rr.readChunkData(idx);
            if (p == null) { System.out.println("chunk idx " + idx + ": 无数据"); continue; }
            NbtNode root = RegionWriter.unpackChunk(p);
            NbtNode level = root.get("Level");
            int cx = ((Number) level.get("xPos").value).intValue();
            int cz = ((Number) level.get("zPos").value).intValue();
            System.out.println("== chunk (" + cx + "," + cz + ")");
            TreeMap<Integer, Integer> c = new TreeMap<Integer, Integer>();
            for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                int[] ids = SectionCodec.decode(sec);
                if (ids == null) continue;
                for (int s : ids) {
                    if (s != 0) c.put(s, c.containsKey(s) ? c.get(s) + 1 : 1);
                }
            }
            for (Map.Entry<Integer, Integer> e : c.entrySet()) {
                int sid = e.getKey();
                String name = snap.lookupBlockName(sid);
                if (name == null) name = "unknown:" + sid;
                System.out.println("  " + sid + " -> " + name + " x" + e.getValue());
            }
        }
    }
}
