package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 快照概要统计：blocks/biomes/mods 数量、原版方块数、最大方块 ID（上界）、
 * 按 modid 的方块数分布、fingerprint。用于验证 registry-snapshot.json 与注册表一致。
 *
 * 用法：SnapshotStats <registry-snapshot.json>
 */
public class SnapshotStats {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: SnapshotStats <registry-snapshot.json>");
            return;
        }
        RegistrySnapshot snap = RegistrySnapshot.load(new File(args[0]));
        System.out.println("snapshot: " + args[0]);
        System.out.println("dataVersion: " + snap.dataVersion + " | gameVersion: " + snap.gameVersion);
        System.out.println("mods: " + snap.mods.size());
        System.out.println("blocks: " + snap.blocks.size() + " | biomes: " + snap.biomes.size());
        System.out.println("fingerprint: " + snap.fingerprint);

        int maxBlockId = -1;
        String maxBlockName = null;
        int vanillaBlocks = 0;
        Map<String, Integer> modBlockCount = new TreeMap<String, Integer>();
        for (RegistrySnapshot.BlockEntry e : snap.blocks) {
            if (e.id > maxBlockId) {
                maxBlockId = e.id;
                maxBlockName = e.name;
            }
            int colon = e.name.indexOf(':');
            String modid = colon > 0 ? e.name.substring(0, colon) : "?";
            Integer c = modBlockCount.get(modid);
            modBlockCount.put(modid, c == null ? 1 : c + 1);
            if (modid.equals("minecraft")) vanillaBlocks++;
        }
        System.out.println("vanilla blocks (minecraft:*): " + vanillaBlocks);
        System.out.println(">>> 最大方块 ID（注册表上界）: " + maxBlockId + " = " + maxBlockName);

        int maxBiomeId = -1;
        String maxBiomeName = null;
        for (RegistrySnapshot.BiomeEntry e : snap.biomes) {
            if (e.id > maxBiomeId) {
                maxBiomeId = e.id;
                maxBiomeName = e.name;
            }
        }
        System.out.println("biome 数量: " + snap.biomes.size() + " | 最大 biome ID: " + maxBiomeId + " = " + maxBiomeName);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(modBlockCount.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        System.out.println("--- 方块数最多的 mod (top 15) ---");
        for (int i = 0; i < Math.min(15, sorted.size()); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            System.out.println("  " + e.getKey() + ": " + e.getValue() + " 方块");
        }
        // 抽样：id 1/2/7/168 的名称
        System.out.println("--- 抽样查名 ---");
        System.out.println("id 1  = " + snap.lookupBlockName(1));
        System.out.println("id 2  = " + snap.lookupBlockName(2));
        System.out.println("id 7  = " + snap.lookupBlockName(7));
        System.out.println("id 168 = " + snap.lookupBlockName(168));
        System.out.println("id 363 = " + snap.lookupBlockName(363));
        System.out.println("id 3321 = " + snap.lookupBlockName(3321));
    }
}
