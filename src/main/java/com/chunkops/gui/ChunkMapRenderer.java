package com.chunkops.gui;

import com.chunkops.core.ExactMapFile;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;
import com.chunkops.verify.RegionReader;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * 2D 区块地图渲染器（阶段 3）：
 * - 文件级读取 region（不经游戏世界加载，大存档流畅）
 * - 地表取列：优先 HeightMap(int[256])，缺失时从 y=255 向下扫描
 * - 取色：MapColorCache（IBakedModel 顶面纹理中心像素 + 高度明暗）
 * - 异步：线程池加载 chunk → 主线程 pump 收割 → DynamicTexture 缓存
 * - LRU：tile 缓存与纹理缓存均有上限（自动淘汰最旧）
 */
public class ChunkMapRenderer {

    private static final int TILE_CACHE_MAX = 16384;   // tile 数据缓存：大存档浏览不驱逐（16MB）
    private static final int TEXTURE_CACHE_MAX = 2048;  // GL 纹理缓存：驱逐后重建很快，不闪烁
    private static final int EXACT_CACHE_MAX = 64;      // 精确层 region 数据缓存（64×512KB≈32MB）
    private static final int BACKGROUND = 0xFF14181C;
    private static final int PENDING = 0xFF20262C;

    private final MapColorCache colors = new MapColorCache();

    /** 精确层（COPM，游戏内采集）region 缓存：文件路径 → 数据。 */
    private final Map<String, ExactMapFile.ExactRegion> exactRegions =
            new LinkedHashMap<String, ExactMapFile.ExactRegion>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, ExactMapFile.ExactRegion> eldest) {
                    return size() > EXACT_CACHE_MAX;
                }
            };

    private final Map<String, ChunkMapTile> tiles = new LinkedHashMap<String, ChunkMapTile>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ChunkMapTile> eldest) {
            return size() > TILE_CACHE_MAX;
        }
    };
    private final Map<String, ResourceLocation> textures = new LinkedHashMap<String, ResourceLocation>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
            return size() > TEXTURE_CACHE_MAX;
        }
    };
    private final Map<String, Future<ChunkMapTile>> pending = new HashMap<String, Future<ChunkMapTile>>();
    /** 纹理命名递增序号（主线程专用，保证唯一）。 */
    private int texSeq = 0;

    private final ExecutorService pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "chunkops-map");
            t.setDaemon(true);
            return t;
        }
    });

    /** 一个 chunk 的 16×16 颜色图。exactCols=精确层（游戏内采集）实际列数。 */
    public static class ChunkMapTile {
        public final int[] colors = new int[256];
        public int exactCols = 0;
    }

    static String key(String worldPath, int cx, int cz) {
        return worldPath + "|" + cx + "," + cz;
    }

    /** 清空全部缓存（切换存档时调用，避免跨世界串图/闪烁）。 */
    public void clear() {
        synchronized (pending) {
            for (Future<ChunkMapTile> f : pending.values()) f.cancel(true);
            pending.clear();
        }
        tiles.clear();
        textures.clear();
        synchronized (exactRegions) {
            exactRegions.clear();
        }
    }

    /**
     * 主线程调用：收割已完成的异步加载，返回 tile（未就绪返回 null 并提交加载）。
     */
    public ChunkMapTile getOrLoad(File worldDir, int cx, int cz) {
        pump();
        String k = key(worldDir.getAbsolutePath(), cx, cz);
        ChunkMapTile tile = tiles.get(k);
        if (tile != null) return tile;
        synchronized (pending) {
            if (!pending.containsKey(k)) {
                final String fWorld = worldDir.getAbsolutePath();
                final int fx = cx;
                final int fz = cz;
                pending.put(k, pool.submit(new java.util.concurrent.Callable<ChunkMapTile>() {
                    public ChunkMapTile call() {
                        return loadTile(new File(fWorld), fx, fz);
                    }
                }));
            }
        }
        return null;
    }

    /** 主线程调用：收割完成的任务。 */
    public void pump() {
            synchronized (pending) {
                Iterator<Map.Entry<String, Future<ChunkMapTile>>> it = pending.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Future<ChunkMapTile>> e = it.next();
                    if (e.getValue().isDone()) {
                        try {
                            ChunkMapTile t = e.getValue().get();
                            if (t != null) tiles.put(e.getKey(), t);
                        } catch (Exception ignored) {
                            // 加载失败：不缓存
                        }
                        it.remove();
                    }
                }
            }
    }

    /** 主线程调用：获取/创建 chunk 纹理（DynamicTexture 16×16，nearest 拉伸由 GL 决定）。 */
    public ResourceLocation textureFor(String key, ChunkMapTile tile, TextureManager tm) {
        ResourceLocation loc = textures.get(key);
        if (loc == null) {
            // 唯一命名（原 hashCode 命名有碰撞风险 → 不同 chunk 复用同一纹理 = "两个一模一样的地方"）
            loc = new ResourceLocation("chunkops", "map/t" + (++texSeq));
            DynamicTexture dt = new DynamicTexture(16, 16);
            int[] data = dt.getTextureData();
            // 关键：1.12.2 DynamicTexture 像素就是 ARGB（0xAARRGGBB）直填。
            // （原 "ABGR 转换"经色板自检实证为错误：红↔蓝交换；文件级"正确"是
            //   sprite 帧 ABGR 提取错(交换1) × 转换(交换2) 双重抵消的假象）
            System.arraycopy(tile.colors, 0, data, 0, 256);
            dt.updateDynamicTexture();
            tm.loadTexture(loc, dt);
            textures.put(key, loc);
        }
        return loc;
    }

    public int pendingColor() {
        return PENDING;
    }

    /** 调试自检：生成一个全 tile 单色的平色数据（与地图 tile 完全相同的渲染路径）。 */
    public ChunkMapTile flatTile(int color) {
        ChunkMapTile t = new ChunkMapTile();
        java.util.Arrays.fill(t.colors, color);
        return t;
    }

    public int background() {
        return BACKGROUND;
    }

    /**
     * 精确层 region 数据（COPM，游戏内采集）：saves/&lt;世界名&gt; → gameDir/chunkops/map/&lt;世界名&gt;/0/。
     * 不存在/不可读返回 null（编辑器回退到文件级取色）。LRU 缓存。
     */
    ExactMapFile.ExactRegion exactRegion(File worldDir, int rx, int rz) {
        File gameDir = worldDir.getParentFile() == null ? null : worldDir.getParentFile().getParentFile();
        if (gameDir == null || !gameDir.isDirectory()) return null;
        File f = new File(new File(new File(gameDir, "chunkops/map"), worldDir.getName()), "0");
        f = new File(f, ExactMapFile.fileName(rx, rz));
        String path = f.getAbsolutePath();
        synchronized (exactRegions) {
            ExactMapFile.ExactRegion r = exactRegions.get(path);
            if (r != null) return r;
            try {
                r = ExactMapFile.read(f);
            } catch (Exception e) {
                return null;
            }
            if (r == null) return null;
            exactRegions.put(path, r);
            return r;
        }
    }

    // ------------------------------------------------------------ load

    /** 后台线程：加载一个 chunk 的 16×16 地表颜色图。 */
    ChunkMapTile loadTile(File worldDir, int cx, int cz) {
        try {
            int rx = Math.floorDiv(cx, 32);
            int rz = Math.floorDiv(cz, 32);
            File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
            if (!region.isFile()) return null;
            RegionReader rr = new RegionReader(region);
            int index = (cz & 31) * 32 + (cx & 31);
            byte[] payload = rr.readChunkData(index);
            if (payload == null) return null;
            NbtNode root = RegionWriter.unpackChunk(payload);
            NbtNode level = root.get("Level");
            if (level == null) return null;

            // 地表高度（HeightMap int[256]，旧式）
            int[] heightMap = null;
            NbtNode hm = level.get("HeightMap");
            if (hm != null && hm.type == NbtNode.TAG_INT_ARRAY) {
                heightMap = (int[]) hm.value;
            }

            // 解码 sections
            Map<Integer, int[]> secStates = new HashMap<Integer, int[]>();
            for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                int[] ids = SectionCodec.decode(sec);
                if (ids != null) secStates.put(SectionCodec.sectionY(sec), ids);
            }
            if (secStates.isEmpty()) return null;

            // 精确层（游戏内采集 COPM）：有数据列优先，其余列走文件级取色回退
            ExactMapFile.ExactRegion exact = exactRegion(worldDir, rx, rz);
            return buildTile(secStates, heightMap, exact, (cx & 31) * 16, (cz & 31) * 16);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从内存 chunk NBT（如剪贴板）构建 tile：无精确层（null），纯文件级取色。
     * 供粘贴预览使用（粘贴内容仍是原版 NBT，无需读盘）。
     */
    public ChunkMapTile tileFromMemory(NbtNode level) {
        try {
            int[] heightMap = null;
            NbtNode hm = level.get("HeightMap");
            if (hm != null && hm.type == NbtNode.TAG_INT_ARRAY) {
                heightMap = (int[]) hm.value;
            }
            Map<Integer, int[]> secStates = new HashMap<Integer, int[]>();
            for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                int[] ids = SectionCodec.decode(sec);
                if (ids != null) secStates.put(SectionCodec.sectionY(sec), ids);
            }
            if (secStates.isEmpty()) return null;
            return buildTile(secStates, heightMap, null, 0, 0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 由 sections + HeightMap + 精确层构建 16×16 颜色 tile（坐标 base 用于精确层索引）。 */
    private ChunkMapTile buildTile(Map<Integer, int[]> secStates, int[] heightMap,
                                   ExactMapFile.ExactRegion exact, int baseX, int baseZ) {        ChunkMapTile tile = new ChunkMapTile();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int col = z * 16 + x;
                if (exact != null) {
                    // 越界防御：旧版 256 宽文件（尺寸 bug）的越界列视为无数据 → 回退文件级
                    if ((baseX + x) < exact.width && (baseZ + z) < exact.height) {
                        int gi = (baseZ + z) * exact.width + (baseX + x);
                        if (exact.hasData(gi)) {
                            // 精确层：v2 起数据存原色，高度明暗显示端统一应用；v1 旧数据已含 shade 不再叠加
                            int c = exact.color[gi];
                            if (exact.version >= 2) {
                                int h = exact.heightY[gi] & 0xFF;
                                // 坡度立体感：东/西/北/南邻列高度（越界用自身，避免 region 边缘缝隙）
                                c = MapColorCache.shadeRelief(c, h,
                                        hgt(exact, baseX + x + 1, baseZ + z, h),
                                        hgt(exact, baseX + x - 1, baseZ + z, h),
                                        hgt(exact, baseX + x, baseZ + z - 1, h),
                                        hgt(exact, baseX + x, baseZ + z + 1, h));
                            }
                            tile.colors[col] = c;
                            tile.exactCols++;
                            continue;
                        }
                    }
                }
                int y = findSurfaceY(secStates, heightMap, col);
                int stateId = 0;
                if (y >= 0) {
                    int[] ids = secStates.get(y >> 4);
                    if (ids != null) {
                        stateId = ids[((y & 15) << 8) | col];
                    }
                }
                if (stateId == 0) {
                    tile.colors[col] = BACKGROUND;
                } else {
                    int c = colors.colorFor(stateId);
                    tile.colors[col] = MapColorCache.shade(c, Math.max(0, Math.min(255, y)));
                }
            }
        }
        return tile;
    }

    /** 精确层邻列高度（越界/无数据 → fallback 自身高度，避免边缘缝隙）。 */
    private static int hgt(ExactMapFile.ExactRegion exact, int x, int z, int fallback) {
        if (x < 0 || z < 0 || x >= exact.width || z >= exact.height) return fallback;
        int gi = z * exact.width + x;
        if (gi < 0 || gi >= exact.heightY.length) return fallback;
        return exact.heightY[gi] & 0xFF;
    }

    /**
     * 地表 y 定位。
     * 实测（HeightMapCheck）：1.12.2 旧式 HeightMap 存「最高方块 y + 1」（= 第一个空气的 y），
     * 且个别列可能过期未更新。策略：从 heightMap-1 向下扫描最多 5 格；失败则从 y=255 全扫。
     */
    static int findSurfaceY(Map<Integer, int[]> secStates, int[] heightMap, int col) {
        if (heightMap != null) {
            int start = Math.max(0, heightMap[col] - 1);
            for (int yy = start; yy >= Math.max(0, start - 5); yy--) {
                int[] ids = secStates.get(yy >> 4);
                if (ids != null && ids[((yy & 15) << 8) | col] != 0) return yy;
            }
        }
        return scanHeight(secStates, col);
    }

    /** 无 HeightMap 或扫描失败时从 y=255 向下全扫。 */
    static int scanHeight(Map<Integer, int[]> secStates, int col) {
        for (int secY = 15; secY >= 0; secY--) {
            int[] ids = secStates.get(secY);
            if (ids == null) continue;
            for (int i = 15; i >= 0; i--) {
                int stateId = ids[(i << 8) | col];
                if (stateId != 0) return secY * 16 + i;
            }
        }
        return -1;
    }
}
