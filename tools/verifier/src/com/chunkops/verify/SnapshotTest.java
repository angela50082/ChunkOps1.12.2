package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;

import java.io.File;

/**
 * RegistrySnapshot 往返测试：构造 → save → load → 查询。
 * 模拟 Forge 模组端 ChunkOpsExport 的输出格式（Gson pretty JSON）。
 */
public class SnapshotTest {

    public static void main(String[] args) throws Exception {
        File out = new File("test-data/registry-snapshot-test.json");
        if (out.getParentFile() != null) out.getParentFile().mkdirs();

        RegistrySnapshot snap = new RegistrySnapshot();
        snap.dataVersion = 1343;
        snap.fingerprint = "deadbeef";
        RegistrySnapshot.BlockEntry b1 = new RegistrySnapshot.BlockEntry();
        b1.name = "minecraft:stone"; b1.id = 1;
        RegistrySnapshot.BlockEntry b2 = new RegistrySnapshot.BlockEntry();
        b2.name = "minecraft:grass"; b2.id = 2;
        RegistrySnapshot.BlockEntry b3 = new RegistrySnapshot.BlockEntry();
        b3.name = "biomesoplenty:test_block"; b3.id = 3321; // 实测最大 ID 附近的模组方块
        snap.blocks.add(b1); snap.blocks.add(b2); snap.blocks.add(b3);
        RegistrySnapshot.BiomeEntry be1 = new RegistrySnapshot.BiomeEntry();
        be1.name = "biomesoplenty:alps"; be1.id = 200;
        snap.biomes.add(be1);

        snap.save(out);
        System.out.println("saved: " + out.getAbsolutePath());

        RegistrySnapshot loaded = RegistrySnapshot.load(out);
        System.out.println("loaded: formatVersion=" + loaded.formatVersion
                + " fingerprint=" + loaded.fingerprint
                + " blocks=" + loaded.blocks.size() + " biomes=" + loaded.biomes.size());
        System.out.println("lookup(1)=" + loaded.lookupBlockName(1));
        System.out.println("lookup(3321)=" + loaded.lookupBlockName(3321));
        System.out.println("lookup(minecraft:stone)=" + loaded.lookupBlockId("minecraft:stone"));
        System.out.println("lookup(biomesoplenty:test_block)=" + loaded.lookupBlockId("biomesoplenty:test_block"));
        System.out.println("biome(200)=" + loaded.lookupBiomeName(200));
        System.out.println("biome(biomesoplenty:alps)=" + loaded.lookupBiomeId("biomesoplenty:alps"));

        boolean ok = loaded.lookupBlockName(3321).equals("biomesoplenty:test_block")
                && loaded.lookupBlockId("minecraft:stone") == 1
                && loaded.lookupBiomeId("biomesoplenty:alps") == 200;
        System.out.println(ok ? "SNAPSHOT TEST PASSED" : "SNAPSHOT TEST FAILED");
        if (!ok) System.exit(1);
    }
}
