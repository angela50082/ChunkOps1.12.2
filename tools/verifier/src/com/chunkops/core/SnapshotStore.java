package com.chunkops.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 注册表快照归档（阶段 A-1）与世界指纹（阶段 A-2）。
 *
 * 背景：1.12.2 存档里的方块只有数字，数字的含义由「当前会话的注册表顺序」决定
 * （stateId = registryId&lt;&lt;4|meta，表由 Block.BLOCK_STATE_IDS 每次启动重建）。
 * mods 目录一变，注册顺序就变，旧存档的数字会被重新解释成别的方块——而游戏无从知道。
 *
 * 对策：把每次会话的注册表快照**留档**，并给每个存档记录「它是在哪份编号下被写的」，
 * 之后就能用两份快照做 name#meta 对齐，把存档里的编号重映射回当前会话（见 {@link RegistryRepair}）。
 *
 * 目录布局：
 *   &lt;gameDir&gt;/registry-snapshot.json                  当前会话（每次启动覆盖，兼容旧行为）
 *   &lt;gameDir&gt;/chunkops/snapshots/&lt;时间戳&gt;-&lt;hash8&gt;.json   历史留档（同内容只留一份）
 *   &lt;存档目录&gt;/chunkops/registry-at-write.json         该存档的编号指纹（随存档一起备份/迁移）
 */
public final class SnapshotStore {

    public static final String SNAPSHOT_NAME = "registry-snapshot.json";
    public static final String WORLD_MARK_NAME = "registry-at-write.json";

    private SnapshotStore() {
    }

    /** &lt;gameDir&gt;/chunkops/snapshots */
    public static File snapshotsDir(File gameDir) {
        return new File(new File(gameDir, "chunkops"), "snapshots");
    }

    /** 把已导出的快照文件归档；同内容（hash 相同）不重复归档。返回归档后的文件。 */
    public static File archiveFile(File gameDir, File snapshotFile) throws IOException {
        byte[] bytes = Files.readAllBytes(snapshotFile.toPath());
        String shortHash = sha256(bytes).substring(0, 8);
        File dir = snapshotsDir(gameDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建快照目录: " + dir);
        }
        File existing = findByHash(dir, shortHash);
        if (existing != null) return existing;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File out = new File(dir, ts + "-" + shortHash + ".json");
        Files.write(out.toPath(), bytes);
        return out;
    }

    /** 在归档目录里按 hash 后缀找已存在的快照。 */
    public static File findByHash(File snapshotsDir, String shortHash) {
        File[] files = snapshotsDir.listFiles();
        if (files == null) return null;
        String suffix = "-" + shortHash + ".json";
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(suffix)) return f;
        }
        return null;
    }

    /** 已归档的快照列表（新 → 旧）。 */
    public static List<File> listArchived(File gameDir) {
        List<File> out = new ArrayList<File>();
        File[] files = snapshotsDir(gameDir).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".json")) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName()); // 文件名前缀是时间戳
            }
        });
        return out;
    }

    // ------------------------------------------------------------ 世界指纹

    /**
     * 存档的编号指纹（几百字节的小文件，随存档一起备份/迁移）：
     * {
     *   "mappingHash": "…",           当前编号映射哈希（与当前会话比对用）
     *   "snapshot": "20260916-…json", 指向 &lt;gameDir&gt;/chunkops/snapshots/ 里的归档快照（修复时要用整份）
     *   "writtenAt": 1789534603635    写入时间（epoch ms）
     * }
     * 注意：这里**不复制**整份快照（587 模组时十几 MB），只存引用；快照本体在 gameDir 的归档目录里。
     */
    public static final class WorldMark {
        public String mappingHash = "";
        public String snapshot = "";
        public long writtenAt;

        public WorldMark() {
        }

        public WorldMark(String mappingHash, String snapshot, long writtenAt) {
            this.mappingHash = mappingHash;
            this.snapshot = snapshot;
            this.writtenAt = writtenAt;
        }
    }

    /** &lt;存档目录&gt;/chunkops/registry-at-write.json */
    public static File worldMarkFile(File worldDir) {
        return new File(new File(worldDir, "chunkops"), WORLD_MARK_NAME);
    }

    public static boolean hasWorldMark(File worldDir) {
        return worldDir != null && worldMarkFile(worldDir).isFile();
    }

    /** 写入存档指纹（小文件，读写都很快，可在切换存档时直接调用）。 */
    public static void writeWorldMark(File worldDir, WorldMark mark) throws IOException {
        File dst = worldMarkFile(worldDir);
        File parent = dst.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"mappingHash\": \"").append(mark.mappingHash).append("\",\n");
        sb.append("  \"snapshot\": \"").append(mark.snapshot).append("\",\n");
        sb.append("  \"writtenAt\": ").append(mark.writtenAt).append("\n}\n");
        Files.write(dst.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 读取存档指纹；没有记录返回 null（损坏时也返回 null，不抛）。 */
    public static WorldMark readWorldMark(File worldDir) {
        if (worldDir == null) return null;
        File f = worldMarkFile(worldDir);
        if (!f.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            Map<String, Object> m = Json.asMap(root);
            WorldMark mark = new WorldMark();
            if (Json.str(m, "mappingHash") != null) mark.mappingHash = Json.str(m, "mappingHash");
            if (Json.str(m, "snapshot") != null) mark.snapshot = Json.str(m, "snapshot");
            if (Json.lng(m, "writtenAt") != null) mark.writtenAt = Json.lng(m, "writtenAt").longValue();
            return mark;
        } catch (Exception e) {
            return null;
        }
    }

    /** 该存档记录的那份归档快照文件（不存在返回 null）。 */
    public static File markSnapshotFile(File gameDir, WorldMark mark) {
        if (mark == null || mark.snapshot == null || mark.snapshot.isEmpty()) return null;
        File f = new File(snapshotsDir(gameDir), mark.snapshot);
        return f.isFile() ? f : null;
    }

    // ------------------------------------------------------------ 哈希

    /**
     * 「编号映射」指纹：只取 blocks/biomes 的 name→id 排序后哈希，
     * 用于判断两份快照的**方块编号是否等价**（模组版本变化但编号没变时应该相等）。
     */
    public static String mappingHash(RegistrySnapshot snap) {
        if (snap == null) return "";
        List<String> lines = new ArrayList<String>();
        for (RegistrySnapshot.BlockEntry e : snap.blocks) {
            lines.add(e.name + "=" + e.id);
        }
        for (RegistrySnapshot.BiomeEntry e : snap.biomes) {
            lines.add("biome:" + e.name + "=" + e.id);
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 取哈希前 8 位（展示用）。 */
    public static String shortHash(String hash) {
        return hash == null || hash.length() < 8 ? String.valueOf(hash) : hash.substring(0, 8);
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }
}
