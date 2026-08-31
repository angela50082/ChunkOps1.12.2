package com.chunkops.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册表快照：blockId ↔ modid:blockname、biomeId ↔ modid:biomename。
 *
 * 来源：Forge 模组端导出（chunkops-export），文件 registry-snapshot.json。
 * 文件模式工具加载后即可把 1.12.2 存档的数字 ID 名称化（阶段 2 重映射的基础）。
 *
 * JSON 结构（与设计文档 5.4 一致）：
 * {
 *   "formatVersion": 1, "gameVersion": "1.12.2", "dataVersion": 1343,
 *   "fingerprint": "sha256(...)", "mods": [{"modid":..,"version":..}],
 *   "blocks": [{"name":"modid:block","id":1234}],
 *   "biomes": [{"name":"modid:biome","id":200}]
 * }
 */
public class RegistrySnapshot {

    public int formatVersion = 1;
    public String gameVersion = "1.12.2";
    public int dataVersion = 1343;
    public String fingerprint = "";
    public final List<Map<String, String>> mods = new ArrayList<Map<String, String>>();
    public final List<BlockEntry> blocks = new ArrayList<BlockEntry>();
    public final List<BiomeEntry> biomes = new ArrayList<BiomeEntry>();

    public static class BlockEntry {
        public String name;
        public int id;
    }

    public static class BiomeEntry {
        public String name;
        public int id;
    }

    // 索引
    private final Map<Integer, String> idToName = new HashMap<Integer, String>();
    private final Map<String, Integer> nameToId = new HashMap<String, Integer>();
    private final Map<Integer, String> biomeIdToName = new HashMap<Integer, String>();
    private final Map<String, Integer> biomeNameToId = new HashMap<String, Integer>();
    private boolean indexed = false;

    public void index() {
        idToName.clear();
        nameToId.clear();
        biomeIdToName.clear();
        biomeNameToId.clear();
        for (BlockEntry e : blocks) {
            idToName.put(e.id, e.name);
            nameToId.put(e.name, e.id);
        }
        for (BiomeEntry e : biomes) {
            biomeIdToName.put(e.id, e.name);
            biomeNameToId.put(e.name, e.id);
        }
        indexed = true;
    }

    public String lookupBlockName(int id) {
        if (!indexed) index();
        return idToName.get(id);
    }

    public Integer lookupBlockId(String name) {
        if (!indexed) index();
        return nameToId.get(name);
    }

    public String lookupBiomeName(int id) {
        if (!indexed) index();
        return biomeIdToName.get(id);
    }

    public Integer lookupBiomeId(String name) {
        if (!indexed) index();
        return biomeNameToId.get(name);
    }

    // ------------------------------------------------------------ IO

    public static RegistrySnapshot load(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return fromJson(Json.parse(text));
    }

    public void save(File file) throws IOException {
        Files.write(file.toPath(), toJsonText().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static RegistrySnapshot fromJson(Object root) {
        RegistrySnapshot snap = new RegistrySnapshot();
        Map<String, Object> m = Json.asMap(root);
        if (Json.lng(m, "formatVersion") != null) snap.formatVersion = Json.lng(m, "formatVersion").intValue();
        if (Json.str(m, "gameVersion") != null) snap.gameVersion = Json.str(m, "gameVersion");
        if (Json.lng(m, "dataVersion") != null) snap.dataVersion = Json.lng(m, "dataVersion").intValue();
        if (Json.str(m, "fingerprint") != null) snap.fingerprint = Json.str(m, "fingerprint");
        Object modsObj = m.get("mods");
        if (modsObj instanceof List) {
            for (Object o : (List<Object>) modsObj) {
                Map<String, Object> mo = Json.asMap(o);
                Map<String, String> mm = new HashMap<String, String>();
                if (mo.get("modid") != null) mm.put("modid", String.valueOf(mo.get("modid")));
                if (mo.get("version") != null) mm.put("version", String.valueOf(mo.get("version")));
                snap.mods.add(mm);
            }
        }
        Object blocksObj = m.get("blocks");
        if (blocksObj instanceof List) {
            for (Object o : (List<Object>) blocksObj) {
                Map<String, Object> bo = Json.asMap(o);
                BlockEntry e = new BlockEntry();
                e.name = Json.str(bo, "name");
                if (e.name == null) continue;
                Long id = Json.lng(bo, "id");
                if (id == null) continue;
                e.id = id.intValue();
                snap.blocks.add(e);
            }
        }
        Object biomesObj = m.get("biomes");
        if (biomesObj instanceof List) {
            for (Object o : (List<Object>) biomesObj) {
                Map<String, Object> bo = Json.asMap(o);
                BiomeEntry e = new BiomeEntry();
                e.name = Json.str(bo, "name");
                if (e.name == null) continue;
                Long id = Json.lng(bo, "id");
                if (id == null) continue;
                e.id = id.intValue();
                snap.biomes.add(e);
            }
        }
        return snap;
    }

    public String toJsonText() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("formatVersion", formatVersion);
        root.put("gameVersion", gameVersion);
        root.put("dataVersion", dataVersion);
        root.put("fingerprint", fingerprint);
        List<Object> modsJson = new ArrayList<Object>();
        for (Map<String, String> mo : mods) {
            Map<String, Object> mm = new LinkedHashMap<String, Object>();
            mm.putAll(mo);
            modsJson.add(mm);
        }
        root.put("mods", modsJson);
        List<Object> blocksJson = new ArrayList<Object>();
        for (BlockEntry e : blocks) {
            Map<String, Object> bo = new LinkedHashMap<String, Object>();
            bo.put("name", e.name);
            bo.put("id", e.id);
            blocksJson.add(bo);
        }
        root.put("blocks", blocksJson);
        List<Object> biomesJson = new ArrayList<Object>();
        for (BiomeEntry e : biomes) {
            Map<String, Object> bo = new LinkedHashMap<String, Object>();
            bo.put("name", e.name);
            bo.put("id", e.id);
            biomesJson.add(bo);
        }
        root.put("biomes", biomesJson);
        return Json.write(root);
    }
}
