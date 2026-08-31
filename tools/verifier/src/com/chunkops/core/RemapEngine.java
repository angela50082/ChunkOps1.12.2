package com.chunkops.core;

import com.chunkops.verify.NbtNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 跨世界重映射引擎（设计文档 6.2 的 8 步管线）。
 *
 * 输入：源 chunk NBT + 源注册表快照 + 目标注册表快照 + 策略选项。
 * 输出：重映射后的 chunk NBT（根级/Level 内所有标签原样保留，只重映射数字 ID）+ 冲突报告。
 *
 * 流程：
 *  1. legacy/palette 解码 → stateId
 *  2. stateId → (id, meta) → 源快照名称化
 *  3. 目标快照查名 → 目标 stateId（缺失 → 策略：fallback / abort / keepRaw）
 *  4. 重建 legacy 数组（保留原 Y 与光照数组）
 *  5. biome 重映射（byte[]/IntArray 兼容；目标 id>255 自动转 IntArray）
 *  6. TE/实体：名称化 id 原样保留（不重映射）
 *  7. 冲突报告 + 干跑
 */
public class RemapEngine {

    /** 策略选项。 */
    public static class Options {
        public int fallbackBlockId = 0;   // 缺失方块回退（0 = air）
        public int fallbackBiomeId = 0;   // 缺失生物群系回退（0 = ocean）
        public boolean abortOnMissing = false; // 任一缺失即中止
        public boolean keepRawIds = false;     // 保留原始数字 ID（危险，仅同模组集）
    }

    /** 冲突报告。 */
    public static class ConflictReport {
        public long blocksTotal = 0;
        public long blocksMapped = 0;
        public long blocksFallenBack = 0;
        public final Map<String, Long> missingBlocks = new TreeMap<String, Long>(); // 源方块名 -> 数量
        public final Map<String, Long> missingBiomes = new TreeMap<String, Long>();
        public final java.util.List<String> notes = new java.util.ArrayList<String>();
        public boolean aborted = false;

        public void addMissing(Map<String, Long> map, String name) {
            Long c = map.get(name);
            map.put(name, c == null ? 1 : c + 1);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("方块: 共 ").append(blocksTotal).append("，映射 ").append(blocksMapped)
                    .append("，回退 ").append(blocksFallenBack);
            if (!missingBlocks.isEmpty()) {
                sb.append("；缺失方块 ").append(missingBlocks.size()).append(" 种：");
                int n = 0;
                for (Map.Entry<String, Long> e : missingBlocks.entrySet()) {
                    if (n++ >= 8) { sb.append("…"); break; }
                    sb.append(e.getKey()).append("x").append(e.getValue()).append(" ");
                }
            }
            if (!missingBiomes.isEmpty()) {
                sb.append("；缺失生物群系 ").append(missingBiomes.size()).append(" 种");
            }
            if (!notes.isEmpty()) sb.append("；说明: ").append(notes.size()).append(" 条");
            if (aborted) sb.append("；[已中止]");
            return sb.toString();
        }
    }

    /**
     * 重映射一个 chunk。
     * @param root 源 chunk NBT（会被就地修改并返回）
     */
    public static NbtNode remapChunk(NbtNode root, RegistrySnapshot src, RegistrySnapshot dst,
                                     Options opt, ConflictReport report) {
        if (root == null) return root;
        NbtNode level = root.get("Level");
        if (level == null || level.type != NbtNode.TAG_COMPOUND) return root;

        // ---- 1-4. sections 方块重映射 ----
        NbtNode sections = level.get("Sections");
        if (sections != null && sections.type == NbtNode.TAG_LIST) {
            List<NbtNode> list = sections.asList();
            Map<Integer, Integer> idCache = new HashMap<Integer, Integer>();
            for (int si = 0; si < list.size(); si++) {
                NbtNode sec = list.get(si);
                int[] stateIds = SectionCodec.decode(sec);
                if (stateIds == null) continue;
                int[] newIds = new int[SectionCodec.BLOCKS_PER_SECTION];
                for (int i = 0; i < stateIds.length; i++) {
                    int stateId = stateIds[i];
                    report.blocksTotal++;
                    if (stateId == 0) {
                        newIds[i] = 0;
                        continue;
                    }
                    int id = stateId >> 4;
                    int meta = stateId & 15;
                    Integer newId = idCache.get(id);
                    if (newId == null) {
                        newId = remapBlockId(id, src, dst, opt, report);
                        idCache.put(id, newId);
                    }
                    if (newId < 0) {
                        if (opt.abortOnMissing) {
                            report.aborted = true;
                            return root;
                        }
                        newIds[i] = opt.fallbackBlockId << 4 | meta;
                        report.blocksFallenBack++;
                        // 按实际方块数记录缺失（不经过 idCache，保证计数准确）
                        String name = src.lookupBlockName(id);
                        report.addMissing(report.missingBlocks, name != null ? name : "unknown-id:" + id);
                    } else {
                        newIds[i] = newId << 4 | meta;
                        report.blocksMapped++;
                    }
                }
                NbtNode newSec = SectionCodec.encodeLegacy(newIds);
                // 保留 Y 与光照
                NbtNode y = sec.get("Y");
                if (y != null) newSec.asMap().put("Y", y);
                for (String light : new String[]{"BlockLight", "SkyLight"}) {
                    NbtNode l = sec.get(light);
                    if (l != null) newSec.asMap().put(light, l);
                }
                list.set(si, newSec);
            }
        }

        // ---- 5. biome 重映射（byte[]/IntArray 兼容） ----
        remapBiomes(level, src, dst, opt, report);

        // ---- 6. TE/实体：原样保留（名称化 id 无需重映射） ----
        report.notes.add("TileEntities/Entities 名称化 id 原样保留");
        return root;
    }

    static int remapBlockId(int id, RegistrySnapshot src, RegistrySnapshot dst, Options opt, ConflictReport report) {
        String name = src.lookupBlockName(id);
        if (name == null) {
            // 源快照也不认识（快照过期/不完整）
            return opt.keepRawIds ? id : -1;
        }
        Integer dstId = dst.lookupBlockId(name);
        if (dstId == null) {
            return opt.keepRawIds ? id : -1;
        }
        return dstId;
    }

    static void remapBiomes(NbtNode level, RegistrySnapshot src, RegistrySnapshot dst,
                            Options opt, ConflictReport report) {
        NbtNode biomesNode = level.get("Biomes");
        if (biomesNode == null) return;
        int[] biomeIds;
        boolean wasByteArray = biomesNode.type == NbtNode.TAG_BYTE_ARRAY;
        if (wasByteArray) {
            byte[] b = (byte[]) biomesNode.value;
            biomeIds = new int[b.length];
            for (int i = 0; i < b.length; i++) biomeIds[i] = b[i] & 0xFF;
        } else if (biomesNode.type == NbtNode.TAG_INT_ARRAY) {
            biomeIds = ((int[]) biomesNode.value).clone();
        } else {
            report.notes.add("Biomes 标签类型异常: " + NbtNode.typeName(biomesNode.type));
            return;
        }
        boolean needIntArray = false;
        for (int i = 0; i < biomeIds.length; i++) {
            int id = biomeIds[i];
            String name = src.lookupBiomeName(id);
            if (name == null) {
                report.addMissing(report.missingBiomes, "unknown-biome:" + id);
                biomeIds[i] = opt.fallbackBiomeId;
                continue;
            }
            Integer dstId = dst.lookupBiomeId(name);
            if (dstId == null) {
                report.addMissing(report.missingBiomes, name);
                biomeIds[i] = opt.fallbackBiomeId;
                continue;
            }
            biomeIds[i] = dstId;
            if (dstId > 255) needIntArray = true;
        }
        if (wasByteArray && !needIntArray) {
            byte[] b = new byte[biomeIds.length];
            for (int i = 0; i < b.length; i++) b[i] = (byte) biomeIds[i];
            level.asMap().put("Biomes", NbtNode.byteArrayNode(b));
        } else {
            level.asMap().put("Biomes", NbtNode.intArrayNode(biomeIds));
        }
    }
}
