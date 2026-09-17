package com.chunkops;

import com.chunkops.core.SnapshotStore;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * registry-snapshot.json 导出器（阶段 1）。
 *
 * 在 Forge 运行时遍历注册表，输出当前模组集的：
 *   blocks: modid:block ↔ 数字 ID（含原版，记录最大 ID）
 *   biomes: modid:biome ↔ 数字 ID
 *   mods + fingerprint（排序后的 modid:version 的 sha256）
 *
 * 文件模式工具（chunkops-core）加载该快照后即可把存档数字 ID 名称化，
 * 也是阶段 2 跨世界重映射的指纹比对依据。
 */
public class ChunkOpsExport {

    /**
     * 存储级 stateId（与存档 palette 项一致）——2026-09-02 定案（REID 存储表语义根治）：
     *
     * JEID/REID 写盘路径（reid$newGetDataForNBT）直接使用 Block.BLOCK_STATE_IDS.get(state)
     * （即 field_176229_d，MCP 名 BLOCK_STATE_IDS，public static final），其值 = 注册表 id<<4|meta
     * （vanilla registerBlocks 填充公式，实测存档：stone=16、water=144、mekanism:oreblock#3=112723）。
     *
     * 注意：Block.getStateId() 在 Forge 1.12.2 被重定义为 id + meta<<12，与存档值不一致
     * （实测：stone→1、water→9、forestry fence→526/4622 —— 全部 id<4096 的方块错位根因！
     * 旧代码反射候选名 "STATE_TO_ID"/"field_176229_d" 等在 MCP 反混淆运行时均不存在，
     * 一直回退到 getStateId，只对 id≥4096（JEID patch 了 getStateId=(id<<4)|meta）恰好正确）。
     * BLOCK_STATE_IDS 是 public 字段，直接访问，无需反射。
     */
    public static int storageStateId(net.minecraft.block.state.IBlockState st) {
        return net.minecraft.block.Block.BLOCK_STATE_IDS.get(st);
    }

    /** 导出到指定目录下的 registry-snapshot.json。返回输出文件。 */
    public static File export(File dir) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("gameVersion", "1.12.2");
        root.addProperty("dataVersion", 1343);

        // blocks（含原版与模组）——REID 语境：存盘 palette 项 = Block.getStateId(state)（state 身份序号），
        // 与注册表 id（getIdFromBlock/JEI）是两套（观察差 21 的固定偏移）。快照必须建
        // 「name#meta ↔ stateId」的 state 级映射（遍历每个方块全部 meta 的 getStateId）。
        JsonArray blocks = new JsonArray();
        int maxBlockId = -1;
        int blockCount = 0;
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
                int sid = storageStateId(st); // 存储级 stateId（与存档 palette 项一致）
                if (sid > maxBlockId) maxBlockId = sid;
                JsonObject o = new JsonObject();
                o.addProperty("name", base + "#" + m);
                o.addProperty("id", sid);
                blocks.add(o);
                blockCount++;
            }
        }
        root.add("blocks", blocks);

        // biomes
        JsonArray biomes = new JsonArray();
        int biomeCount = 0;
        for (Biome b : Biome.REGISTRY) {
            ResourceLocation rl = Biome.REGISTRY.getNameForObject(b);
            if (rl == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", rl.toString());
            o.addProperty("id", Biome.getIdForBiome(b));
            biomes.add(o);
            biomeCount++;
        }
        root.add("biomes", biomes);

        // mods + fingerprint
        JsonArray mods = new JsonArray();
        List<String> sorted = new ArrayList<String>();
        for (ModContainer mc : Loader.instance().getActiveModList()) {
            JsonObject o = new JsonObject();
            o.addProperty("modid", mc.getModId());
            o.addProperty("version", mc.getVersion());
            mods.add(o);
            sorted.add(mc.getModId() + ":" + mc.getVersion());
        }
        root.add("mods", mods);
        Collections.sort(sorted);
        root.addProperty("fingerprint", sha256(join(sorted, "|")));

        File out = new File(dir, "registry-snapshot.json");
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(out), StandardCharsets.UTF_8);
        try {
            w.write(json);
        } finally {
            w.close();
        }

        // 编号映射指纹（阶段 A）：把「name#meta→stateId」排序后哈希，写成 registry-snapshot.hash 边车文件。
        // 编辑器切换存档时只需读这个小文件即可判断「这个存档是不是在当前编号下写的」，不必解析整份快照
        // （587 模组时快照十几 MB，解析要几秒）。
        String mappingHash = mappingHash(blocks, biomes);
        try {
            java.io.OutputStreamWriter hw = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(new File(dir, "registry-snapshot.hash")),
                    StandardCharsets.UTF_8);
            try {
                hw.write(mappingHash);
            } finally {
                hw.close();
            }
        } catch (IOException ignored) {
            // 边车文件写失败不影响主流程（编辑器会退回自行计算）
        }

        System.out.println("[ChunkOps] registry-snapshot.json 导出: " + out.getAbsolutePath()
                + " (blocks=" + blockCount + ", maxBlockId=" + maxBlockId
                + ", biomes=" + biomeCount + ", mods=" + mods.size()
                + ", mappingHash=" + SnapshotStore.shortHash(mappingHash) + ")");
        return out;
    }

    /**
     * 编号映射指纹：与 {@link com.chunkops.core.SnapshotStore#mappingHash} 必须完全一致
     * （同样的行格式、同样的排序、同样的 sha256），世界指纹就是靠它比对的。
     */
    static String mappingHash(JsonArray blocks, JsonArray biomes) {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject o = blocks.get(i).getAsJsonObject();
            lines.add(o.get("name").getAsString() + "=" + o.get("id").getAsInt());
        }
        for (int i = 0; i < biomes.size(); i++) {
            JsonObject o = biomes.get(i).getAsJsonObject();
            lines.add("biome:" + o.get("name").getAsString() + "=" + o.get("id").getAsInt());
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sha256(sb.toString());
    }

    static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
