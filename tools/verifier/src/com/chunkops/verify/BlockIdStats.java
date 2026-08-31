package com.chunkops.verify;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块 ID 统计器（清单 4 补充：1.12.2 存档实测的方块 ID 分布）。
 *
 * 遍历指定存档（可限制 chunk 数），逐 section 解码 legacy 数组
 * （Blocks + Add 高 4 位），统计：最大方块 ID、id>255 方块数量、
 * 高频高 ID 方块等。模组方块 ID 的分布特征对阶段 2 重映射有参考价值。
 *
 * 用法：BlockIdStats <worldDir> [maxChunks] [main]
 *      第三个参数为 "main" 时只扫描主世界 region（跳过 DIM 目录）。
 */
public class BlockIdStats {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: BlockIdStats <worldDir> [maxChunks] [main]");
            return;
        }
        File world = new File(args[0]);
        int maxChunks = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        List<File> regionFiles;
        if (args.length > 2 && args[2].equals("main")) {
            regionFiles = new ArrayList<File>();
            File regionDir = new File(world, "region");
            if (regionDir.isDirectory()) {
                for (File f : regionDir.listFiles()) {
                    if (f.getName().toLowerCase().endsWith(".mca")) regionFiles.add(f);
                }
            }
        } else {
            regionFiles = RegionReader.collectRegionFiles(world);
        }
        int chunks = 0, sections = 0, blocks = 0;
        int maxId = 0;
        long blocksOver255 = 0;
        Map<Integer, Integer> idCount = new HashMap<Integer, Integer>(); // 所有 id（含原版）
        Map<Integer, Integer> highIdCount = new HashMap<Integer, Integer>(); // id > 255
        long[] idHist256 = new long[16]; // id/256 桶

        outer:
        for (File rf : regionFiles) {
            RegionReader rr = new RegionReader(rf);
            for (int idx : rr.listChunkIndices()) {
                if (chunks >= maxChunks) break outer;
                byte[] data;
                try {
                    data = rr.readChunkData(idx);
                } catch (IOException e) {
                    continue;
                }
                if (data == null) continue;
                chunks++;
                NbtNode root;
                try {
                    root = NbtReader.readChunkPayload(data);
                } catch (IOException e) {
                    continue;
                }
                NbtNode level = root.get("Level");
                if (level == null) continue;
                NbtNode secs = level.get("Sections");
                if (secs == null) continue;
                for (NbtNode sec : secs.asList()) {
                    NbtNode blocksNode = sec.get("Blocks");
                    if (blocksNode == null) continue;
                    sections++;
                    byte[] blk = (byte[]) blocksNode.value;
                    NbtNode addNode = sec.get("Add");
                    byte[] add = addNode != null ? (byte[]) addNode.value : null;
                    for (int i = 0; i < blk.length; i++) {
                        int id = blk[i] & 0xFF;
                        if (add != null) {
                            int nib = (i & 1) == 0 ? (add[i >> 1] & 0xF) : ((add[i >> 1] >> 4) & 0xF);
                            id |= nib << 8;
                        }
                        blocks++;
                        if (id > maxId) maxId = id;
                        Integer c = idCount.get(id);
                        idCount.put(id, c == null ? 1 : c + 1);
                        idHist256[id >> 8]++;
                        if (id > 255) {
                            blocksOver255++;
                            Integer h = highIdCount.get(id);
                            highIdCount.put(id, h == null ? 1 : h + 1);
                        }
                    }
                }
            }
        }

        System.out.println("world: " + world);
        System.out.println("scanned chunks: " + chunks + " | sections: " + sections + " | blocks: " + blocks);
        System.out.println("max block id: " + maxId);
        System.out.println("blocks with id > 255 (modded range): " + blocksOver255
                + " (" + String.format("%.2f", 100.0 * blocksOver255 / Math.max(1, blocks)) + "%)");
        StringBuilder hist = new StringBuilder("id/256 bucket histogram: ");
        for (int i = 0; i < idHist256.length; i++) hist.append("[" + (i * 256) + "-" + (i * 256 + 255) + "]=" + idHist256[i] + "  ");
        System.out.println(hist);

        // 高频高 ID 方块 top 15
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<Map.Entry<Integer, Integer>>(highIdCount.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<Integer, Integer>>() {
            public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        System.out.println("top high-id blocks (id > 255, by count):");
        for (int i = 0; i < Math.min(15, sorted.size()); i++) {
            Map.Entry<Integer, Integer> e = sorted.get(i);
            System.out.println("  id=" + e.getKey() + " (" + (e.getKey() >> 4) + ":" + (e.getKey() & 15) + ") x" + e.getValue());
        }
        if (sorted.isEmpty()) System.out.println("  (none)");
    }
}
