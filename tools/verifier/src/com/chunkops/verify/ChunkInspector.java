package com.chunkops.verify;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块结构检查器（阶段 0 格式验证）。
 *
 * 用法：
 *   ChunkInspector <世界目录> <chunkX> <chunkZ>     # dump 单个区块结构
 *   ChunkInspector <世界目录> --scan [maxChunks]    # 扫描全部 region 统计
 *
 * 验证设计文档第 10 节清单 1-5、9 条：
 *  1. section 是否同时含 Blocks/Add/Data 旧数组
 *  2. chunk NBT 根结构（DataVersion 位置、Level 包裹）与压缩类型
 *  3. palette 位打包（4 bit 下限、索引顺序 y<<8|z<<4|x）
 *  4. 原版方块注册 ID 上界（由 palette 最大 stateId 推断）
 *  5. Heightmaps 标签
 *  9. 直接读磁盘 chunk（AnvilChunkLoader 等价路径）可行性
 */
public class ChunkInspector {

    static class SectionInfo {
        int y;
        NbtNode palette;
        long[] blockStates;
        boolean hasLegacyBlocks, hasLegacyAdd, hasLegacyData;
        boolean hasBlockLight, hasSkyLight;
        int paletteEntryType = -1;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("  ChunkInspector <worldDir> <chunkX> <chunkZ>");
            System.out.println("  ChunkInspector <worldDir> --scan [maxChunks]");
            return;
        }
        File world = new File(args[0]);
        if (!world.isDirectory()) {
            System.out.println("ERROR: not a directory: " + world);
            return;
        }
        if (args.length >= 2 && args[1].equals("--scan")) {
            int max = args.length >= 3 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
            scan(world, max);
        } else if (args.length >= 3) {
            int cx = Integer.parseInt(args[1]);
            int cz = Integer.parseInt(args[2]);
            dumpChunk(world, cx, cz);
        } else {
            System.out.println("Usage: see above");
        }
    }

    // ---------------------------------------------------------------- dump

    static void dumpChunk(File world, int cx, int cz) throws IOException {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        File regionFile = new File(world, "region" + File.separator + "r." + rx + "." + rz + ".mca");
        if (!regionFile.isFile()) {
            System.out.println("ERROR: region file not found: " + regionFile);
            return;
        }
        int index = (cz & 31) * 32 + (cx & 31);
        RegionReader rr = new RegionReader(regionFile);
        byte[] chunkData = rr.readChunkData(index);
        if (chunkData == null) {
            System.out.println("chunk (" + cx + "," + cz + ") not present in " + regionFile.getName());
            return;
        }
        NbtNode root = NbtReader.readChunkPayload(chunkData);
        System.out.println("=== chunk (" + cx + "," + cz + ") from " + regionFile.getName() + " (index " + index + ") ===");
        System.out.println("root name: " + root.name + " | keys: " + keyList(root));

        NbtNode dataVersion = root.get("DataVersion");
        System.out.println("DataVersion at root: " + (dataVersion != null ? dataVersion.intValue() : "MISSING (in Level?)"));

        NbtNode level = root.get("Level");
        if (level == null || level.type != NbtNode.TAG_COMPOUND) {
            System.out.println("ERROR: no Level compound (flat chunk format? not 1.12)");
            return;
        }
        System.out.println("Level keys: " + keyList(level));
        NbtNode lvDV = level.get("DataVersion");
        if (lvDV != null) System.out.println("  (DataVersion also inside Level: " + lvDV.intValue() + ")");
        NbtNode sections = level.get("Sections");
        if (sections == null) {
            System.out.println("Sections: none (empty chunk)");
        } else {
            System.out.println("Sections: " + sections.asList().size());
            int expectedBitsSum = 0;
            for (NbtNode sec : sections.asList()) {
                SectionInfo si = parseSection(sec);
                System.out.println("  --- section Y=" + si.y + " ---");
                System.out.println("    palette type: " + NbtNode.typeName(si.paletteEntryType)
                        + " size=" + (si.palette != null ? si.palette.asList().size() : -1)
                        + " | BlockStates len=" + (si.blockStates != null ? si.blockStates.length : -1) + " longs");
                System.out.println("    legacy arrays: Blocks=" + si.hasLegacyBlocks
                        + " Add=" + si.hasLegacyAdd + " Data=" + si.hasLegacyData
                        + " | BlockLight=" + si.hasBlockLight + " SkyLight=" + si.hasSkyLight);
                if (si.paletteEntryType == NbtNode.TAG_INT && si.palette != null && si.blockStates != null) {
                    int bits = bitsFor(si.palette.asList().size());
                    int expectedLongs = (4096 * bits + 63) / 64;
                    System.out.println("    bits/block = max(4,ceil(log2(paletteSize))) = " + bits
                            + " | expected longs = " + expectedLongs
                            + " | match = " + (expectedLongs == si.blockStates.length ? "YES" : "NO***"));
                    System.out.println("    palette[0..7]: " + samplePalette(si.palette, 8));
                    System.out.println("    decoded (id,meta): " + sampleDecoded(si.palette, 8));
                    // 索引顺序验证：y=0 层前 16 个 (x=0..15, z=0)
                    long[] firstRow = new long[16];
                    for (int x = 0; x < 16; x++) {
                        firstRow[x] = getPacked(si.blockStates, x, bits);
                    }
                    System.out.println("    y=0,z=0,x=0..15 packed values: " + join(firstRow));
                    // 显式索引抽查: (x=1,z=2,y=0) -> idx=2*16+1=33
                    long v = getPacked(si.blockStates, (0 << 8) | (2 << 4) | 1, bits);
                    System.out.println("    (x=1,y=0,z=2) packed=" + v + " (expect = values[33])");
                } else if (si.hasLegacyBlocks) {
                    // legacy 数组格式（1.8 及更早的写入器遗留，1.12 读取端必须兼容）
                    NbtNode blocksNode = sec.get("Blocks");
                    NbtNode dataNode = sec.get("Data");
                    NbtNode addNode = sec.get("Add");
                    byte[] blocks = (byte[]) blocksNode.value;
                    byte[] data = dataNode != null ? (byte[]) dataNode.value : null;
                    byte[] add = addNode != null ? (byte[]) addNode.value : null;
                    System.out.println("    [legacy arrays] Blocks=" + blocks.length
                            + " Data=" + (data != null ? data.length : -1)
                            + " Add=" + (add != null ? add.length : -1));
                    StringBuilder sb = new StringBuilder("    y=0,z=0,x=0..7 id:meta = ");
                    for (int x = 0; x < 8; x++) {
                        int idx = x; // y=0, z=0
                        int id = blocks[idx] & 0xFF;
                        if (add != null) id |= nibble(add[idx >> 1], (idx & 1) == 0) << 8;
                        int meta = data != null ? nibble(data[idx >> 1], (idx & 1) == 0) : 0;
                        sb.append(id).append(":").append(meta).append("  ");
                    }
                    System.out.println(sb);
                    System.out.println("    [nibble rule] x even -> low nibble, x odd -> high nibble (vanilla NibbleArray)");
                }
            }
            System.out.println("bits sum across sections (info): " + expectedBitsSum);
        }

        NbtNode heightmaps = level.get("Heightmaps");
        if (heightmaps != null) {
            List<String> keys = new ArrayList<String>();
            for (String k : heightmaps.asMap().keySet()) keys.add(k + ":" + NbtNode.typeName(heightmaps.get(k).type));
            System.out.println("Heightmaps keys: " + keys);
        } else {
            System.out.println("Heightmaps: ABSENT");
        }
        NbtNode legacyHm = level.get("HeightMap");
        if (legacyHm != null) {
            System.out.println("HeightMap (legacy): type=" + NbtNode.typeName(legacyHm.type)
                    + (legacyHm.type == NbtNode.TAG_INT_ARRAY ? " len=" + ((int[]) legacyHm.value).length : ""));
        }
        NbtNode biomes = level.get("Biomes");
        if (biomes != null) {
            String detail;
            if (biomes.type == NbtNode.TAG_BYTE_ARRAY) {
                detail = ((byte[]) biomes.value).length + " bytes (byte[])";
            } else if (biomes.type == NbtNode.TAG_INT_ARRAY) {
                detail = ((int[]) biomes.value).length + " ints (IntArray! non-standard, tool-rewritten world)";
            } else {
                detail = NbtNode.typeName(biomes.type);
            }
            System.out.println("Biomes: " + detail);
        } else {
            System.out.println("Biomes: ABSENT");
        }
        NbtNode tes = level.get("TileEntities");
        if (tes != null) {
            System.out.println("TileEntities: " + tes.asList().size());
            for (int i = 0; i < Math.min(3, tes.asList().size()); i++) {
                NbtNode id = tes.asList().get(i).get("id");
                System.out.println("  TE[" + i + "].id = " + (id != null ? id.stringValue() : "(no id)"));
            }
        }
        NbtNode ents = level.get("Entities");
        if (ents != null) {
            System.out.println("Entities: " + ents.asList().size());
            for (int i = 0; i < Math.min(3, ents.asList().size()); i++) {
                NbtNode id = ents.asList().get(i).get("id");
                System.out.println("  E[" + i + "].id = " + (id != null ? id.stringValue() : "(no id)"));
            }
        }
    }

    static SectionInfo parseSection(NbtNode sec) {
        SectionInfo si = new SectionInfo();
        si.y = sec.get("Y").intValue();
        NbtNode palette = sec.get("Palette");
        NbtNode bs = sec.get("BlockStates");
        if (palette != null && palette.type == NbtNode.TAG_LIST) {
            si.palette = palette;
            si.paletteEntryType = palette.asList().isEmpty() ? -1 : palette.asList().get(0).type;
        }
        if (bs != null && bs.type == NbtNode.TAG_LONG_ARRAY) {
            si.blockStates = (long[]) bs.value;
        }
        si.hasLegacyBlocks = sec.get("Blocks") != null;
        si.hasLegacyAdd = sec.get("Add") != null;
        si.hasLegacyData = sec.get("Data") != null;
        si.hasBlockLight = sec.get("BlockLight") != null;
        si.hasSkyLight = sec.get("SkyLight") != null;
        return si;
    }

    static int bitsFor(int paletteSize) {
        int bits = 4;
        while ((1L << bits) < paletteSize) bits++;
        return bits;
    }

    /** NibbleArray 语义：evenIndex 取低半字节，oddIndex 取高半字节。 */
    static int nibble(byte b, boolean evenIndex) {
        return evenIndex ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    static long getPacked(long[] arr, int index, int bits) {
        int bitIndex = index * bits;
        int longIndex = bitIndex >>> 6;
        int offset = bitIndex & 63;
        long value;
        if (offset + bits <= 64) {
            value = arr[longIndex] >>> offset;
        } else {
            value = (arr[longIndex] >>> offset) | (arr[longIndex + 1] << (64 - offset));
        }
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        return value & mask;
    }

    static String samplePalette(NbtNode palette, int n) {
        List<NbtNode> list = palette.asList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).value);
        }
        if (list.size() > n) sb.append(", ...(").append(list.size()).append(")");
        return sb.append("]").toString();
    }

    static String sampleDecoded(NbtNode palette, int n) {
        List<NbtNode> list = palette.asList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            if (i > 0) sb.append(", ");
            int stateId = ((Number) list.get(i).value).intValue();
            sb.append("(").append(stateId >> 4).append(",").append(stateId & 15).append(")");
        }
        return sb.append("]").toString();
    }

    static String join(long[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.append("]").toString();
    }

    static String keyList(NbtNode compound) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String k : compound.asMap().keySet()) {
            if (!first) sb.append(", ");
            sb.append(k);
            first = false;
        }
        return sb.append("]").toString();
    }

    // ---------------------------------------------------------------- scan

    static void scan(File world, int maxChunks) throws IOException {
        List<File> regionFiles = RegionReader.collectRegionFiles(world);
        System.out.println("scan: " + world + " -> " + regionFiles.size() + " region file(s)");

        Map<Integer, Integer> dataVersions = new LinkedHashMap<Integer, Integer>();
        int totalChunks = 0, scannedChunks = 0;
        int chunksWithLegacyBlocks = 0, chunksWithLegacyData = 0, chunksWithLegacyAdd = 0;
        int sectionsTotal = 0, sectionsWithLegacy = 0;
        int emptyChunks = 0, corruptChunks = 0;
        boolean firstDumped = false;
        int chunksWithHeightmaps = 0, chunksWithBiomes = 0;
        int maxStateId = 0;
        Map<Integer, Integer> paletteEntryTypes = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Integer> bitsHistogram = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Integer> compressionTypes = new LinkedHashMap<Integer, Integer>();
        int mismatchLongs = 0;
        String mismatchExample = "";

        for (File rf : regionFiles) {
            RegionReader rr = new RegionReader(rf);
            List<Integer> indices = rr.listChunkIndices();
            for (int idx : indices) {
                totalChunks++;
                if (scannedChunks >= maxChunks) continue;
                scannedChunks++;
                byte[] data;
                try {
                    data = rr.readChunkData(idx);
                } catch (IOException e) {
                    corruptChunks++;
                    continue;
                }
                if (data == null) {
                    emptyChunks++;
                    continue;
                }
                // 压缩类型
                int comp = data[4] & 0xFF;
                Integer c = compressionTypes.get(comp);
                compressionTypes.put(comp, c == null ? 1 : c + 1);
                NbtNode root = NbtReader.readChunkPayload(data);
                if (!firstDumped) {
                    printChunkStructure(root, rf.getName(), idx);
                    firstDumped = true;
                }
                NbtNode dv = root.get("DataVersion");
                int dvVal = dv != null ? dv.intValue() : -1;
                Integer d = dataVersions.get(dvVal);
                dataVersions.put(dvVal, d == null ? 1 : d + 1);
                NbtNode level = root.get("Level");
                if (level == null || level.type != NbtNode.TAG_COMPOUND) continue;
                if (level.get("Heightmaps") != null) chunksWithHeightmaps++;
                if (level.get("Biomes") != null) chunksWithBiomes++;
                NbtNode sections = level.get("Sections");
                if (sections == null) continue;
                boolean hasLegacyBlocks = false, hasLegacyData = false, hasLegacyAdd = false;
                for (NbtNode sec : sections.asList()) {
                    sectionsTotal++;
                    SectionInfo si = parseSection(sec);
                    if (si.hasLegacyBlocks) hasLegacyBlocks = true;
                    if (si.hasLegacyData) hasLegacyData = true;
                    if (si.hasLegacyAdd) hasLegacyAdd = true;
                    if (si.paletteEntryType >= 0) {
                        Integer p = paletteEntryTypes.get(si.paletteEntryType);
                        paletteEntryTypes.put(si.paletteEntryType, p == null ? 1 : p + 1);
                    }
                    if (si.palette != null) {
                        int size = si.palette.asList().size();
                        int bits = bitsFor(size);
                        Integer b = bitsHistogram.get(bits);
                        bitsHistogram.put(bits, b == null ? 1 : b + 1);
                        if (si.paletteEntryType == NbtNode.TAG_INT) {
                            for (NbtNode e : si.palette.asList()) {
                                int stateId = ((Number) e.value).intValue();
                                if (stateId > maxStateId) maxStateId = stateId;
                            }
                        }
                        if (si.blockStates != null) {
                            int expectedLongs = (4096 * bits + 63) / 64;
                            if (expectedLongs != si.blockStates.length) {
                                mismatchLongs++;
                                if (mismatchExample.isEmpty()) {
                                    mismatchExample = "chunk idx=" + idx + " in " + rf.getName() + " secY=" + si.y
                                            + " bits=" + bits + " got=" + si.blockStates.length + " expected=" + expectedLongs;
                                }
                            }
                        }
                    }
                }
                if (hasLegacyBlocks) chunksWithLegacyBlocks++;
                if (hasLegacyData) chunksWithLegacyData++;
                if (hasLegacyAdd) chunksWithLegacyAdd++;
            }
        }
        System.out.println("total chunks (indexed): " + totalChunks + ", scanned: " + scannedChunks);
        System.out.println("DataVersion histogram: " + dataVersions);
        System.out.println("compression types (1=gzip 2=zlib 3=none): " + compressionTypes);
        System.out.println("sections total: " + sectionsTotal);
        System.out.println("palette entry types: " + paletteEntryTypes);
        System.out.println("bits/block histogram: " + bitsHistogram);
        System.out.println("chunks containing legacy Blocks: " + chunksWithLegacyBlocks
                + " | legacy Data: " + chunksWithLegacyData
                + " | legacy Add: " + chunksWithLegacyAdd);
        System.out.println("chunks with Heightmaps: " + chunksWithHeightmaps + "/" + scannedChunks);
        System.out.println("chunks with Biomes: " + chunksWithBiomes + "/" + scannedChunks);
        System.out.println("max stateId in palettes: " + maxStateId
                + " -> max blockId ~ " + (maxStateId >> 4) + " (meta " + (maxStateId & 15) + ")");
        System.out.println("BlockStates length mismatches: " + mismatchLongs + (mismatchLongs > 0 ? "  e.g. " + mismatchExample : ""));
        System.out.println("empty chunk slots: " + emptyChunks + " | corrupt/oversized slots: " + corruptChunks);
    }

    /** 精简打印一个 chunk 的结构（scan 首个样本用）。 */
    static void printChunkStructure(NbtNode root, String regionName, int index) {
        System.out.println("=== first parsed chunk: " + regionName + " idx=" + index + " ===");
        System.out.println("root keys: " + keyList(root));
        NbtNode dvn = root.get("DataVersion");
        System.out.println("DataVersion: " + (dvn != null ? dvn.intValue() : "null"));
        NbtNode lvl = root.get("Level");
        if (lvl == null) {
            System.out.println("Level: ABSENT (flat format?)");
            return;
        }
        System.out.println("Level keys: " + keyList(lvl));
        NbtNode secs = lvl.get("Sections");
        if (secs == null) {
            System.out.println("Sections: none");
            return;
        }
        for (NbtNode sec : secs.asList()) {
            SectionInfo si = parseSection(sec);
            System.out.println("  secY=" + si.y
                    + " paletteSize=" + (si.palette != null ? si.palette.asList().size() : -1)
                    + " entryType=" + NbtNode.typeName(si.paletteEntryType)
                    + " blockStates=" + (si.blockStates != null ? si.blockStates.length : -1) + " longs"
                    + " legacy(Blocks/Add/Data)=" + si.hasLegacyBlocks + "/" + si.hasLegacyAdd + "/" + si.hasLegacyData
                    + " light=" + si.hasBlockLight + "/" + si.hasSkyLight);
            if (si.paletteEntryType == NbtNode.TAG_INT && si.palette.asList().size() > 0 && si.blockStates != null) {
                System.out.println("    palette[0..7] decoded (id,meta): " + sampleDecoded(si.palette, 8));
                int bits = bitsFor(si.palette.asList().size());
                long[] row = new long[16];
                for (int x = 0; x < 16; x++) row[x] = getPacked(si.blockStates, x, bits);
                System.out.println("    bits=" + bits + " | y=0,z=0,x=0..15 values: " + join(row));
            }
        }
    }
}
