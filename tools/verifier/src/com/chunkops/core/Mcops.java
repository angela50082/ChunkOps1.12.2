package com.chunkops.core;

import com.chunkops.verify.NbtNode;
import com.chunkops.verify.NbtReader;
import com.chunkops.verify.NbtWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * .mcops 名称化剪贴板格式（设计文档 5.4）：
 *
 *   gzip NBT：{ Format:1, Kind:"chunks", DataVersion, Source:{fingerprint,note},
 *               Count, Chunks:[ { Pos:[x,z], Sections:[{Y, Palette:["modid:block#meta",...], Blocks:int[]}],
 *                                Biomes:["modid:biome",...], TileEntities:[...], Entities:[...],
 *                                RootExtra:{...}, LevelExtra:{...} } ] }
 *
 * 特点：方块/生物群系用「名称 + meta」存储（跨模组集可移植）；
 * TE/实体/根级与 Level 额外标签原样保留；导入时经目标快照重映射回数字 ID。
 */
public class Mcops {

    public static final int FORMAT = 1;

    /** 导入报告。 */
    public static class ImportReport {
        public int chunks = 0;
        public int sections = 0;
        public int paletteEntries = 0;
        public long blocksFallenBack = 0;
        public final Map<String, Long> missingPalette = new TreeMap<String, Long>(); // 缺失的方块名
        public String summary() {
            return "chunks=" + chunks + ", sections=" + sections + ", palette=" + paletteEntries
                    + ", 缺失回退=" + blocksFallenBack + " (" + missingPalette.size() + " 种)";
        }
    }

    // ------------------------------------------------------------ export

    /**
     * 导出 chunk 列表为 .mcops 字节。
     * @param chunkRoots 源 chunk NBT 根节点列表（会被读取但只读）
     * @param snap 源注册表快照（名称化用）
     * @param fingerprint 源模组集 fingerprint（记录在 Source）
     * @param note 备注
     */
    public static byte[] exportChunks(List<NbtNode> chunkRoots, RegistrySnapshot snap,
                                      String fingerprint, String note) throws IOException {
        NbtNode root = NbtNode.compound();
        root.asMap().put("Format", NbtNode.intNode(FORMAT));
        root.asMap().put("Kind", NbtNode.stringNode("chunks"));
        root.asMap().put("DataVersion", NbtNode.intNode(1343));
        NbtNode source = NbtNode.compound();
        source.asMap().put("fingerprint", NbtNode.stringNode(fingerprint == null ? "" : fingerprint));
        source.asMap().put("note", NbtNode.stringNode(note == null ? "" : note));
        root.asMap().put("Source", source);

        NbtNode chunks = NbtNode.list();
        chunks.listElemType = NbtNode.TAG_COMPOUND;
        for (NbtNode chunkRoot : chunkRoots) {
            chunks.asList().add(exportChunk(chunkRoot, snap));
        }
        root.asMap().put("Chunks", chunks);
        root.asMap().put("Count", NbtNode.intNode(chunkRoots.size()));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(NbtWriter.writeRoot(root));
        gz.close();
        return bos.toByteArray();
    }

    static NbtNode exportChunk(NbtNode chunkRoot, RegistrySnapshot snap) {
        NbtNode out = NbtNode.compound();
        NbtNode level = chunkRoot.get("Level");
        int x = 0, z = 0;
        if (level != null) {
            NbtNode xp = level.get("xPos");
            NbtNode zp = level.get("zPos");
            if (xp != null) x = ((Number) xp.value).intValue();
            if (zp != null) z = ((Number) zp.value).intValue();
        }
        NbtNode pos = NbtNode.list();
        pos.listElemType = NbtNode.TAG_INT;
        pos.asList().add(NbtNode.intNode(x));
        pos.asList().add(NbtNode.intNode(z));
        out.asMap().put("Pos", pos);

        if (level != null) {
            // Sections → 名称化 palette
            NbtNode sectionsOut = NbtNode.list();
            sectionsOut.listElemType = NbtNode.TAG_COMPOUND;
            NbtNode sections = level.get("Sections");
            if (sections != null && sections.type == NbtNode.TAG_LIST) {
                for (NbtNode sec : sections.asList()) {
                    int[] stateIds = SectionCodec.decode(sec);
                    if (stateIds == null) continue;
                    sectionsOut.asList().add(encodeSection(stateIds, SectionCodec.sectionY(sec), snap));
                }
            }
            out.asMap().put("Sections", sectionsOut);

            // Biomes → 名称
            NbtNode biomes = level.get("Biomes");
            if (biomes != null) {
                int[] ids;
                if (biomes.type == NbtNode.TAG_BYTE_ARRAY) {
                    byte[] b = (byte[]) biomes.value;
                    ids = new int[b.length];
                    for (int i = 0; i < b.length; i++) ids[i] = b[i] & 0xFF;
                } else if (biomes.type == NbtNode.TAG_INT_ARRAY) {
                    ids = (int[]) biomes.value;
                } else {
                    ids = new int[0];
                }
                NbtNode biomesOut = NbtNode.list();
                biomesOut.listElemType = NbtNode.TAG_STRING;
                for (int id : ids) {
                    String name = snap.lookupBiomeName(id);
                    biomesOut.asList().add(NbtNode.stringNode(name != null ? name : "unknown:" + id));
                }
                out.asMap().put("Biomes", biomesOut);
            }

            // TE/Entities 原样
            NbtNode tes = level.get("TileEntities");
            if (tes != null) out.asMap().put("TileEntities", tes);
            NbtNode ents = level.get("Entities");
            if (ents != null) out.asMap().put("Entities", ents);

            // LevelExtra：除已处理外的所有标签
            NbtNode levelExtra = NbtNode.compound();
            for (Map.Entry<String, NbtNode> e : level.asMap().entrySet()) {
                String k = e.getKey();
                if (k.equals("Sections") || k.equals("Biomes") || k.equals("TileEntities") || k.equals("Entities")) continue;
                levelExtra.asMap().put(k, e.getValue());
            }
            if (!levelExtra.asMap().isEmpty()) out.asMap().put("LevelExtra", levelExtra);
        }

        // RootExtra：根级除 Level/DataVersion 外的标签（模组数据）
        NbtNode rootExtra = NbtNode.compound();
        for (Map.Entry<String, NbtNode> e : chunkRoot.asMap().entrySet()) {
            String k = e.getKey();
            if (k.equals("Level") || k.equals("DataVersion")) continue;
            rootExtra.asMap().put(k, e.getValue());
        }
        if (!rootExtra.asMap().isEmpty()) out.asMap().put("RootExtra", rootExtra);
        return out;
    }

    /** section → {Y, Palette:[名称#meta], Blocks:int[4096] 索引} */
    static NbtNode encodeSection(int[] stateIds, int y, RegistrySnapshot snap) {
        Map<Integer, String> idToName = new HashMap<Integer, String>();
        List<String> palette = new ArrayList<String>();
        int[] indices = new int[stateIds.length];
        for (int i = 0; i < stateIds.length; i++) {
            int stateId = stateIds[i];
            String name = idToName.get(stateId);
            if (name == null) {
                if (stateId == 0) {
                    name = "minecraft:air#0";
                } else {
                    int id = stateId >> 4;
                    int meta = stateId & 15;
                    String n = snap != null ? snap.lookupBlockName(id) : null;
                    name = (n != null ? n : "unknown:" + id) + "#" + meta;
                }
                idToName.put(stateId, name);
                palette.add(name);
            }
            indices[i] = palette.indexOf(name);
        }
        NbtNode sec = NbtNode.compound();
        sec.asMap().put("Y", NbtNode.byteNode((byte) y));
        NbtNode pal = NbtNode.list();
        pal.listElemType = NbtNode.TAG_STRING;
        for (String p : palette) pal.asList().add(NbtNode.stringNode(p));
        sec.asMap().put("Palette", pal);
        sec.asMap().put("Blocks", NbtNode.intArrayNode(indices));
        return sec;
    }

    // ------------------------------------------------------------ import

    /**
     * 导入 .mcops 字节 → chunk NBT 根节点列表（经目标快照重映射回数字 ID）。
     */
    public static List<NbtNode> importChunks(byte[] data, RegistrySnapshot snap, ImportReport report) throws IOException {
        List<NbtNode> result = new ArrayList<NbtNode>();
        NbtNode root;
        try {
            root = NbtReader.read(new GZIPInputStream(new ByteArrayInputStream(data)), false);
        } catch (IOException e) {
            // 尝试非 gzip（兼容性）
            root = NbtReader.readRaw(data);
        }
        NbtNode chunks = root.get("Chunks");
        if (chunks == null || chunks.type != NbtNode.TAG_LIST) {
            throw new IOException("mcops 缺少 Chunks 列表");
        }
        for (NbtNode c : chunks.asList()) {
            result.add(importChunk(c, snap, report));
        }
        return result;
    }

    static NbtNode importChunk(NbtNode mc, RegistrySnapshot snap, ImportReport report) {
        NbtNode chunkRoot = NbtNode.compound();
        chunkRoot.asMap().put("DataVersion", NbtNode.intNode(1343));
        NbtNode level = NbtNode.compound();
        chunkRoot.asMap().put("Level", level);

        NbtNode pos = mc.get("Pos");
        if (pos != null && pos.type == NbtNode.TAG_LIST && pos.asList().size() >= 2) {
            level.asMap().put("xPos", pos.asList().get(0));
            level.asMap().put("zPos", pos.asList().get(1));
        }

        // Sections 反向
        NbtNode sectionsIn = mc.get("Sections");
        if (sectionsIn != null && sectionsIn.type == NbtNode.TAG_LIST) {
            NbtNode sectionsOut = NbtNode.list();
            sectionsOut.listElemType = NbtNode.TAG_COMPOUND;
            for (NbtNode secIn : sectionsIn.asList()) {
                NbtNode secOut = decodeSection(secIn, snap, report);
                if (secOut != null) sectionsOut.asList().add(secOut);
            }
            level.asMap().put("Sections", sectionsOut);
        }

        // Biomes 反向（名称 → id；缺失 → 0）
        NbtNode biomesIn = mc.get("Biomes");
        if (biomesIn != null && biomesIn.type == NbtNode.TAG_LIST) {
            List<NbtNode> names = biomesIn.asList();
            int[] ids = new int[names.size()];
            boolean needInt = false;
            for (int i = 0; i < names.size(); i++) {
                String name = ((String) names.get(i).value);
                int id = 0;
                if (name.startsWith("unknown:")) {
                    try { id = Integer.parseInt(name.substring("unknown:".length())); } catch (NumberFormatException ignored) { }
                } else {
                    Integer dstId = snap.lookupBiomeId(name);
                    if (dstId != null) {
                        id = dstId;
                        if (dstId > 255) needInt = true;
                    }
                }
                ids[i] = id;
            }
            if (needInt) {
                level.asMap().put("Biomes", NbtNode.intArrayNode(ids));
            } else {
                byte[] b = new byte[ids.length];
                for (int i = 0; i < b.length; i++) b[i] = (byte) ids[i];
                level.asMap().put("Biomes", NbtNode.byteArrayNode(b));
            }
        }

        // TE/Entities 原样
        NbtNode tes = mc.get("TileEntities");
        if (tes != null) level.asMap().put("TileEntities", tes);
        NbtNode ents = mc.get("Entities");
        if (ents != null) level.asMap().put("Entities", ents);

        // LevelExtra / RootExtra 原样
        NbtNode levelExtra = mc.get("LevelExtra");
        if (levelExtra != null && levelExtra.type == NbtNode.TAG_COMPOUND) {
            level.asMap().putAll(levelExtra.asMap());
        }
        NbtNode rootExtra = mc.get("RootExtra");
        if (rootExtra != null && rootExtra.type == NbtNode.TAG_COMPOUND) {
            chunkRoot.asMap().putAll(rootExtra.asMap());
        }
        report.chunks++;
        return chunkRoot;
    }

    static NbtNode decodeSection(NbtNode secIn, RegistrySnapshot snap, ImportReport report) {
        NbtNode palIn = secIn.get("Palette");
        NbtNode blocksIn = secIn.get("Blocks");
        if (palIn == null || blocksIn == null) return null;
        List<NbtNode> pal = palIn.asList();
        int[] indices = (int[]) blocksIn.value;
        report.sections++;
        report.paletteEntries += pal.size();
        int[] stateIds = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int idx = indices[i];
            if (idx < 0 || idx >= pal.size()) {
                stateIds[i] = 0;
                continue;
            }
            stateIds[i] = nameToStateId((String) pal.get(idx).value, snap, report);
        }
        int y = 0;
        NbtNode yNode = secIn.get("Y");
        if (yNode != null) y = ((Number) yNode.value).intValue();
        NbtNode secOut = SectionCodec.encodeLegacy(stateIds);
        secOut.asMap().put("Y", NbtNode.byteNode((byte) y));
        return secOut;
    }

    /** "modid:block#meta" → stateId；unknown: 保留原 ID；缺失 → 0 并计数。 */
    static int nameToStateId(String name, RegistrySnapshot snap, ImportReport report) {
        int meta = 0;
        int hash = name.lastIndexOf('#');
        if (hash >= 0) {
            try {
                meta = Integer.parseInt(name.substring(hash + 1));
            } catch (NumberFormatException ignored) { }
            name = name.substring(0, hash);
        }
        if (name.startsWith("unknown:")) {
            try {
                int id = Integer.parseInt(name.substring("unknown:".length()));
                return id << 4 | meta;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        Integer id = snap.lookupBlockId(name);
        if (id == null) {
            report.blocksFallenBack++;
            Long c = report.missingPalette.get(name);
            report.missingPalette.put(name, c == null ? 1 : c + 1);
            return 0; // air
        }
        return id << 4 | meta;
    }
}
