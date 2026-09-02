package com.chunkops;

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
                int sid = Block.getStateId(st);
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
        System.out.println("[ChunkOps] registry-snapshot.json 导出: " + out.getAbsolutePath()
                + " (blocks=" + blockCount + ", maxBlockId=" + maxBlockId
                + ", biomes=" + biomeCount + ", mods=" + mods.size() + ")");
        return out;
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
