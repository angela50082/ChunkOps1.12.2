package com.chunkops.gui;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.ChunkOpsTool;
import com.chunkops.verify.NbtNode;
import com.chunkops.verify.RegionReader;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 编辑器操作封装（阶段 3）：GUI 调用 chunkops-core 的区块操作，
 * 含 session.lock 检测与活注册表统计（游戏内无需快照即可名称化）。
 */
public class GuiOps {

    /** 存档是否被占用（session.lock 存在）。注意：游戏关闭后可能残留。 */
    public static boolean hasSessionLock(File worldDir) {
        return new File(worldDir, "session.lock").isFile();
    }

    public static String removeChunk(File worldDir, int cx, int cz, boolean dryRun) {
        try {
            ChunkOpsTool.opRemove(worldDir, cx, cz, dryRun);
            return "移除区块 (" + cx + "," + cz + ")" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "移除失败: " + e.getMessage();
        }
    }

    public static String clearChunk(File worldDir, int cx, int cz, boolean dryRun) {
        try {
            ChunkOpsTool.opClear(worldDir, cx, cz, dryRun);
            return "清空区块 (" + cx + "," + cz + ")" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "清空失败: " + e.getMessage();
        }
    }

    /** 剪裁：删除选区外的全部区块。 */
    public static String trimArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz, boolean dryRun) {
        try {
            ChunkOpsTool.opTrim(worldDir, minCx * 16, minCz * 16, maxCx * 16 + 15, maxCz * 16 + 15, dryRun);
            return "剪裁完成（保留 [" + minCx + ".." + maxCx + ", " + minCz + ".." + maxCz + "]）" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "剪裁失败: " + e.getMessage();
        }
    }

    /**
     * 复制选区：读取 chunk 原始 payload 到内存剪贴板（同模组集原样）。
     *
     * 跨会话编号漂移说明（2026-09-02 实测定案）：部分模组（EnderIO/Forestry/quark/玄钢类）
     * 每次启动注册号都变；旧会话写入的这类方块编号在当前会话无法解码（unknown → 保留原值），
     * 且**绝不能**尝试经游戏读盘+重存"修复"——旧编号可能被当前表误读成别的方块并写回磁盘，
     * 会损坏源存档。正确做法：用户在当前会话重建这些方块后再复制。
     */
    public static java.util.Map<Long, byte[]> copyArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        java.util.Map<Long, byte[]> clipboard = new java.util.HashMap<Long, byte[]>();
        try {
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload != null) {
                        clipboard.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), payload);
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return clipboard;
    }

    /**
     * 粘贴：把剪贴板 chunk 写到「以 (targetCx,targetCz) 为左上角的目标区域」
     * （剪贴板内容按源选区 origin 偏移平移），同模组集原样写入，自动建目录/备份。
     * 返回日志文本。
     */
    public static String pasteArea(File worldDir, java.util.Map<Long, byte[]> clipboard,
                                   int originCx, int originCz, int targetCx, int targetCz) {
        if (clipboard == null || clipboard.isEmpty()) return "剪贴板为空";
        int n = 0;
        int errors = 0;
        // 目标存在则记录（日志用）
        for (java.util.Map.Entry<Long, byte[]> e : clipboard.entrySet()) {
            long key = e.getKey();
            int srcCx = (int) (key >> 32);
            int srcCz = (int) (key & 0xFFFFFFFFL);
            int dx = srcCx - originCx;
            int dz = srcCz - originCz;
            int cx = targetCx + dx;
            int cz = targetCz + dz;
            try {
                int rx = Math.floorDiv(cx, 32);
                int rz = Math.floorDiv(cz, 32);
                File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                File parent = region.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) return "目标缺少 region 目录";
                com.chunkops.core.RegionWriter rw = new com.chunkops.core.RegionWriter(region);
                rw.setChunk((cz & 31) * 32 + (cx & 31), e.getValue());
                rw.write();
                n++;
            } catch (Exception ex) {
                errors++;
                if (errors <= 3) {
                    logLastError = "粘贴失败 (" + cx + "," + cz + "): " + ex.getMessage();
                }
            }
        }
        if (errors > 0) return "粘贴完成: " + n + " 个区块（含 " + errors + " 个失败: " + logLastError + "）";
        return "粘贴完成: " + n + " 个区块 → 目标 (" + targetCx + "," + targetCz + ")";
    }

    private static String logLastError = "";

    /** 世界格式检测：读任一 region 的任一 section，若 Palette 为 int[] → JEID 索引格式。 */
    private static boolean isJeidWorld(File worldDir) {
        File regDir = new File(worldDir, "region");
        if (!regDir.isDirectory()) return false;
        File[] files = regDir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (!f.getName().endsWith(".mca")) continue;
            try {
                com.chunkops.verify.RegionReader rr = new com.chunkops.verify.RegionReader(f);
                for (int i = 0; i < 1024; i++) {
                    byte[] payload = rr.readChunkData(i);
                    if (payload == null) continue;
                    com.chunkops.verify.NbtNode root = com.chunkops.core.RegionWriter.unpackChunk(payload);
                    com.chunkops.verify.NbtNode level = root.get("Level");
                    if (level == null) continue;
                    for (com.chunkops.verify.NbtNode sec : com.chunkops.core.SectionCodec.sectionsOf(level)) {
                        com.chunkops.verify.NbtNode pal = sec.get("Palette");
                        if (pal != null) return pal.value instanceof int[];
                    }
                }
            } catch (Exception ignored) {
                // 单文件读取失败：尝试下一个
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 名称化剪贴板（跨模组集安全）

    /** 活注册表快照（会话级缓存）。优先使用 registry-snapshot.json（模组 init 导出，经验证保真）；
     * 缺失时内存构建。REID 环境下内存遍历与文件快照存在差异（实测：enderio/forestry 部分方块
     * 名称化错位），必须统一走文件快照。 */
    private static com.chunkops.core.RegistrySnapshot liveSnap = null;

    public static synchronized com.chunkops.core.RegistrySnapshot liveSnapshot() {
        if (liveSnap == null) {
            try {
                File f = new File(net.minecraftforge.fml.common.Loader.instance()
                        .getConfigDir().getParentFile(), "registry-snapshot.json");
                if (f.isFile()) {
                    liveSnap = com.chunkops.core.RegistrySnapshot.load(f);
                }
            } catch (Exception ignored) {
                // 文件不可用 → 内存构建
            }
            if (liveSnap == null) {
                com.chunkops.core.RegistrySnapshot s = new com.chunkops.core.RegistrySnapshot();
                for (Block b : Block.REGISTRY) {
                    ResourceLocation rl = Block.REGISTRY.getNameForObject(b);
                    if (rl == null) continue;
                    String base = rl.toString();
                    for (int m = 0; m < 16; m++) {
                        net.minecraft.block.state.IBlockState st;
                        try {
                            st = b.getStateFromMeta(m);
                        } catch (Exception e) {
                            continue;
                        }
                        if (st == null || st.getBlock() != b) continue;
                        com.chunkops.core.RegistrySnapshot.BlockEntry e =
                                new com.chunkops.core.RegistrySnapshot.BlockEntry();
                        e.name = base + "#" + m; // 名称#meta → 存储级 stateId（与存档 palette 项一致）
                        e.id = com.chunkops.ChunkOpsExport.storageStateId(st);
                        s.blocks.add(e);
                    }
                }
                for (Biome b : Biome.REGISTRY) {
                    ResourceLocation rl = Biome.REGISTRY.getNameForObject(b);
                    if (rl == null) continue;
                    com.chunkops.core.RegistrySnapshot.BiomeEntry e =
                            new com.chunkops.core.RegistrySnapshot.BiomeEntry();
                    e.name = rl.toString();
                    e.id = Biome.getIdForBiome(b);
                    s.biomes.add(e);
                }
                s.index();
                liveSnap = s;
            }
        }
        return liveSnap;
    }

    /** 把原始剪贴板 payload 导出为名称化 .mcops（跨模组集安全：方块/生物群系按名+meta 存储）。 */
    public static byte[] exportClipboardNamed(java.util.Map<Long, byte[]> clipboard) {
        try {
            java.util.List<com.chunkops.verify.NbtNode> roots = new java.util.ArrayList<com.chunkops.verify.NbtNode>();
            for (byte[] payload : clipboard.values()) {
                roots.add(com.chunkops.core.RegionWriter.unpackChunk(payload));
            }
            return com.chunkops.core.Mcops.exportChunks(roots, liveSnapshot(), "", "gui-clipboard");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 名称化粘贴：.mcops → 活注册表反解（按名+meta 重建数字 ID）→ 平移写入目标区域。
     * 性能：按 region 分组批量写入（每 region 一次读+写+备份——避免逐 chunk 全量重写导致主线程卡死）。
     * JEID 世界检测后写对应格式（JEID reid mixin 无 legacy 分支，写 legacy 会越界崩溃，实测定案）。
     */
    public static String pasteAreaNamed(File worldDir, byte[] mcops,
                                        int originCx, int originCz, int targetCx, int targetCz) {
        if (mcops == null || mcops.length == 0) return "剪贴板为空";
        try {
            boolean jeid = isJeidWorld(worldDir);
            com.chunkops.core.Mcops.ImportReport report = new com.chunkops.core.Mcops.ImportReport();
            java.util.List<com.chunkops.verify.NbtNode> roots =
                    com.chunkops.core.Mcops.importChunks(mcops, liveSnapshot(), report, jeid);
            int n = 0, errors = 0;
            String lastErr = "";
            // 整体平移量（方块坐标）：TE/实体坐标随 chunk 一起平移（否则多方块机器错位卡死，实测定案）
            int dxb = (targetCx - originCx) * 16;
            int dzb = (targetCz - originCz) * 16;
            // 按 region 分组：Map<regionKey, RegionWriter>
            java.util.Map<String, Object[]> regionWriters = new java.util.LinkedHashMap<String, Object[]>();
            for (com.chunkops.verify.NbtNode root : roots) {
                try {
                    com.chunkops.verify.NbtNode level = root.get("Level");
                    if (level == null) continue;
                    int srcX = ((Number) level.get("xPos").value).intValue();
                    int srcZ = ((Number) level.get("zPos").value).intValue();
                    int dx = srcX - originCx;
                    int dz = srcZ - originCz;
                    int cx = targetCx + dx;
                    int cz = targetCz + dz;
                    level.asMap().put("xPos", com.chunkops.verify.NbtNode.intNode(cx));
                    level.asMap().put("zPos", com.chunkops.verify.NbtNode.intNode(cz));
                    // ---- 平移动 TileEntities 的 x/y/z（否则多方块机器错位卡死，实测定案） ----
                    com.chunkops.verify.NbtNode tes = level.get("TileEntities");
                    if (tes != null && tes.type == com.chunkops.verify.NbtNode.TAG_LIST) {
                        for (com.chunkops.verify.NbtNode te : tes.asList()) {
                            for (String k : new String[]{"x", "y", "z"}) {
                                com.chunkops.verify.NbtNode v = te.get(k);
                                if (v != null) {
                                    int ov = ((Number) v.value).intValue();
                                    int nv = k.equals("y") ? ov : (k.equals("x") ? ov + dxb : ov + dzb);
                                    te.asMap().put(k, com.chunkops.verify.NbtNode.intNode(nv));
                                }
                            }
                        }
                    }
                    // ---- 平移 Entities 的 Pos（及 LeashedTo） ----
                    com.chunkops.verify.NbtNode ents = level.get("Entities");
                    if (ents != null && ents.type == com.chunkops.verify.NbtNode.TAG_LIST) {
                        for (com.chunkops.verify.NbtNode ent : ents.asList()) {
                            com.chunkops.verify.NbtNode pos = ent.get("Pos");
                            if (pos != null && pos.type == com.chunkops.verify.NbtNode.TAG_LIST
                                    && pos.asList().size() >= 3) {
                                double x = ((Number) pos.asList().get(0).value).doubleValue() + dxb;
                                double z = ((Number) pos.asList().get(2).value).doubleValue() + dzb;
                                pos.asList().set(0, com.chunkops.verify.NbtNode.doubleNode(x));
                                pos.asList().set(2, com.chunkops.verify.NbtNode.doubleNode(z));
                            }
                            com.chunkops.verify.NbtNode leash = ent.get("LeashedTo");
                            if (leash != null && leash.type == com.chunkops.verify.NbtNode.TAG_COMPOUND) {
                                for (String k : new String[]{"X", "Z"}) {
                                    com.chunkops.verify.NbtNode v = leash.get(k);
                                    if (v != null) {
                                        double ov = ((Number) v.value).doubleValue();
                                        double nv = k.equals("X") ? ov + dxb : ov + dzb;
                                        leash.asMap().put(k, com.chunkops.verify.NbtNode.doubleNode(nv));
                                    }
                                }
                            }
                        }
                    }
                    byte[] payload = com.chunkops.core.RegionWriter.packChunk(root);
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    String rk = rx + "," + rz;
                    Object[] rwObj = regionWriters.get(rk);
                    if (rwObj == null) {
                        File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                        File parent = region.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) return "目标缺少 region 目录";
                        rwObj = new Object[]{new com.chunkops.core.RegionWriter(region), region};
                        regionWriters.put(rk, rwObj);
                    }
                    ((com.chunkops.core.RegionWriter) rwObj[0]).setChunk((cz & 31) * 32 + (cx & 31), payload);
                    n++;
                } catch (Exception ex) {
                    errors++;
                    lastErr = ex.getMessage();
                }
            }
            // 每 region 一次写盘（自动备份 + 原子写）
            for (Object[] rwObj : regionWriters.values()) {
                try {
                    ((com.chunkops.core.RegionWriter) rwObj[0]).write();
                } catch (Exception ex) {
                    errors++;
                    lastErr = ex.getMessage();
                }
            }
            // 回读校验（防御）：写入的 chunk 必须可重新完整解析
            int verifyFail = 0;
            for (Object[] rwObj : regionWriters.values()) {
                try {
                    File region = (File) rwObj[1];
                    com.chunkops.verify.RegionReader rr = new com.chunkops.verify.RegionReader(region);
                    for (int i = 0; i < 1024; i++) {
                        byte[] back = rr.readChunkData(i);
                        if (back == null) continue;
                        com.chunkops.verify.NbtNode root = com.chunkops.core.RegionWriter.unpackChunk(back);
                        if (root.get("Level") == null) verifyFail++;
                    }
                } catch (Exception ex) {
                    verifyFail++;
                }
            }
            if (verifyFail > 0) {
                return "粘贴完成: " + n + " 个区块 → 目标 (" + targetCx + "," + targetCz
                        + ")，但回读校验失败 " + verifyFail + " 个（数据异常，请勿进游戏并反馈日志）";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("粘贴完成: ").append(n).append(" 个区块 → 目标 (").append(targetCx)
                    .append(",").append(targetCz).append(")，region ").append(regionWriters.size()).append(" 个");
            if (report.blocksFallenBack > 0) {
                sb.append("；缺失方块回退 ").append(report.blocksFallenBack)
                        .append(" 个（").append(report.missingPalette.size()).append(" 种: ");
                int cnt = 0;
                for (java.util.Map.Entry<String, Long> e : report.missingPalette.entrySet()) {
                    if (cnt++ >= 5) { sb.append("…"); break; }
                    sb.append(e.getKey()).append("x").append(e.getValue()).append(" ");
                }
                sb.append("）");
            }
            if (errors > 0) sb.append("；含 ").append(errors).append(" 个失败: ").append(lastErr);
            return sb.toString();
        } catch (Exception e) {
            return "粘贴失败: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ stats

    /**
     * 选区统计（结构版，供 UI 列表）：方块名级计数。
     * 返回 Object[]{Integer chunks, Long total, Integer unknownTypes, Long unknownCount, List<String[]> entries}
     * entries[i] = {方块名, 数量, 百分比字符串}（按数量降序，最多 500 条）。
     */
    public static Object[] statsAreaList(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        try {
            Map<String, Long> blockCount = new TreeMap<String, Long>();
            long total = 0;
            long unknownTotal = 0;
            int unknownTypes = 0;
            int chunks = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload == null) continue;
                    chunks++;
                    NbtNode root = RegionWriter.unpackChunk(payload);
                    NbtNode level = root.get("Level");
                    if (level == null) continue;
                    for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                        int[] stateIds = SectionCodec.decode(sec);
                        if (stateIds == null) continue;
                        for (int s : stateIds) {
                            total++;
                            if (s == 0) continue;
                            int id = s >> 4;
                            ResourceLocation rl = Block.REGISTRY.getNameForObject(Block.getBlockById(id));
                            if (rl == null) {
                                unknownTotal++;
                                continue;
                            }
                            String name = rl.toString();
                            Long c = blockCount.get(name);
                            blockCount.put(name, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            unknownTypes = 0; // 未知 id 类型数不在此统计（见文本版）
            List<String[]> sorted = new ArrayList<String[]>();
            List<Map.Entry<String, Long>> list = new ArrayList<Map.Entry<String, Long>>(blockCount.entrySet());
            Collections.sort(list, new Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            for (int i = 0; i < Math.min(500, list.size()); i++) {
                Map.Entry<String, Long> e = list.get(i);
                sorted.add(new String[]{e.getKey(), String.valueOf(e.getValue()),
                        String.format("%.2f%%", 100.0 * e.getValue() / Math.max(1, total))});
            }
            return new Object[]{Integer.valueOf(chunks), Long.valueOf(total),
                    Integer.valueOf(unknownTypes), Long.valueOf(unknownTotal), sorted};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选区统计：解码方块 → 活注册表名称化 → 按 modid 分桶。
     * 返回多行文本（总数 + top 12 modid + 未知 id 数）。
     */
    public static String statsArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        try {
            Map<String, Long> modCount = new TreeMap<String, Long>();
            Map<Integer, Long> unknownIds = new TreeMap<Integer, Long>();
            long total = 0;
            int chunks = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload == null) continue;
                    chunks++;
                    NbtNode root = RegionWriter.unpackChunk(payload);
                    NbtNode level = root.get("Level");
                    if (level == null) continue;
                    for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                        int[] stateIds = SectionCodec.decode(sec);
                        if (stateIds == null) continue;
                        for (int s : stateIds) {
                            total++;
                            if (s == 0) continue;
                            int id = s >> 4;
                            ResourceLocation rl = Block.REGISTRY.getNameForObject(Block.getBlockById(id));
                            if (rl == null) {
                                Long c = unknownIds.get(id);
                                unknownIds.put(id, c == null ? 1 : c + 1);
                                continue;
                            }
                            String name = rl.toString();
                            int colon = name.indexOf(':');
                            String modid = colon > 0 ? name.substring(0, colon) : name;
                            Long c = modCount.get(modid);
                            modCount.put(modid, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(modCount.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            long unknownTotal = 0;
            for (long v : unknownIds.values()) unknownTotal += v;
            StringBuilder sb = new StringBuilder();
            sb.append("统计: ").append(chunks).append(" 区块 / ").append(total).append(" 方块");
            for (int i = 0; i < Math.min(12, sorted.size()); i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue())
                        .append(String.format(" (%.2f%%)", 100.0 * e.getValue() / Math.max(1, total)));
            }
            sb.append("\n  未知 id: ").append(unknownTotal).append(" 方块 / ").append(unknownIds.size()).append(" 种");
            return sb.toString();
        } catch (Exception e) {
            return "统计失败: " + e.getMessage();
        }
    }
}
