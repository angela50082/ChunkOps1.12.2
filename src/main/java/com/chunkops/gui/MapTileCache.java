package com.chunkops.gui;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 文件级着色瓦片持久缓存（Xaero 式：把"解码 region → 上色"的昂贵结果 bake 成最终像素落盘）。
 *
 * 每 chunk 一个文件，存 256 个 ARGB 色块（最终渲染色，已含群系调色 + 高度/坡度明暗）。
 * 失效依据 = region 头的 chunk 时间戳（游戏/RegionWriter 每次重写 chunk 都会更新该戳），
 * 不匹配即重新解码并覆盖写回。纯 Java8，无游戏依赖，后台加载线程调用。
 */
final class MapTileCache {

    private static final int MAGIC = 0x434F5043; // "COPC"
    private static final int VERSION = 2;        // 调色公式变更时 +1 全量失效
    private static final int SIZE = 256;

    private MapTileCache() {
    }

    static File fileFor(File gameDir, String worldName, int cx, int cz) {
        return new File(new File(new File(gameDir, "chunkops/cache"), worldName),
                "c." + cx + "." + cz + ".cache");
    }

    /** 命中且时间戳一致返回 256 ARGB 色块，否则 null。 */
    static int[] load(File f, int expectedTs) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
            try {
                if (in.readInt() != MAGIC) return null;
                if (in.readInt() != VERSION) return null;
                if (in.readInt() != expectedTs) return null;
                int[] c = new int[SIZE];
                for (int i = 0; i < SIZE; i++) c[i] = in.readInt();
                return c;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static void save(File f, int ts, int[] colors) {
        try {
            File p = f.getParentFile();
            if (!p.isDirectory() && !p.mkdirs()) return;
            File tmp = new File(p, f.getName() + ".tmp");
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(ts);
                for (int c : colors) out.writeInt(c);
            } finally {
                out.close();
            }
            f.delete();
            tmp.renameTo(f);
        } catch (Exception ignored) {
            // 缓存写失败：静默（下次重新解码）
        }
    }
}
