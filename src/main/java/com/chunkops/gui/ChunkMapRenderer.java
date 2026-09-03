package com.chunkops.gui;

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
 * - 取色：MapColor × 生物群系调色 × 高度+坡度明暗（地图模组式）
 * - 持久缓存：MapTileCache 每 chunk bake 最终像素落盘（按 region 头时间戳失效）
 * - 异步：线程池加载 chunk → 主线程 pump 收割 → DynamicTexture 缓存
 * - LRU：tile 缓存与纹理缓存均有上限（自动淘汰最旧）
 */
public class ChunkMapRenderer {

    private static final int TILE_CACHE_MAX = 16384;   // tile 数据缓存：大存档浏览不驱逐（16MB）
    private static final int TEXTURE_CACHE_MAX = 2048;  // GL 纹理缓存：驱逐后重建很快，不闪烁
    private static final int BACKGROUND = 0xFF14181C;
    private static final int PENDING = 0xFF20262C;

    private final MapColorCache colors = new MapColorCache();

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
    /** 负缓存：磁盘不存在的 chunk（虚空/未探索），会话内不再反复提交加载任务（2026-09-03 卡顿根因修复）。 */
    private final java.util.Set<String> absent = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 每帧新提交任务预算：超限下帧继续（防止放大视野时一次性灌爆线程池队列）。 */
    private static final int SUBMIT_BUDGET = 160;
    private int frameSubmitted = 0;
    /** 纹理命名递增序号（主线程专用，保证唯一）。 */
    private int texSeq = 0;

    private final ExecutorService pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "chunkops-map");
            t.setDaemon(true);
            return t;
        }
    });

    /** 一个 chunk 的 16×16 颜色图。 */
    public static class ChunkMapTile {
        public final int[] colors = new int[256];
    }

    static String key(String worldPath, int cx, int cz) {
        return worldPath + "|" + cx + "," + cz;
    }

    /** 清空全部缓存（切换存档/世界修改时调用；同时清负缓存，允许重试新出现的区块）。 */
    public void clear() {
        synchronized (pending) {
            for (Future<ChunkMapTile> f : pending.values()) f.cancel(true);
            pending.clear();
        }
        tiles.clear();
        textures.clear();
        absent.clear();
        frameSubmitted = 0;
    }

    /**
     * 主线程调用：收割已完成的异步加载，返回 tile（未就绪返回 null；无此区块→负缓存跳过，不再提交）。
     * 调用方每帧应先 pump() 一次。
     */
    public ChunkMapTile getOrLoad(File worldDir, int cx, int cz) {
        String k = key(worldDir.getAbsolutePath(), cx, cz);
        ChunkMapTile tile = tiles.get(k);
        if (tile != null) return tile;
        if (absent.contains(k)) return null; // 负缓存：磁盘无此区块，不再反复提交
        synchronized (pending) {
            if (!pending.containsKey(k)) {
                if (frameSubmitted >= SUBMIT_BUDGET) return null; // 本帧预算耗尽：下帧继续
                frameSubmitted++;
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

    /** 主线程调用（每帧一次）：重置预算并收割完成的任务；失败的 chunk 记入负缓存。 */
    public void pump() {
        frameSubmitted = 0;
        synchronized (pending) {
            Iterator<Map.Entry<String, Future<ChunkMapTile>>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Future<ChunkMapTile>> e = it.next();
                if (e.getValue().isDone()) {
                    try {
                        ChunkMapTile t = e.getValue().get();
                        if (t != null) {
                            tiles.put(e.getKey(), t);
                        } else {
                            absent.add(e.getKey()); // 加载失败/无此区块 → 负缓存（本会话不再重试）
                        }
                    } catch (Exception ignored) {
                        absent.add(e.getKey());
                    }
                    it.remove();
                }
            }
        }
    }

    /** 当前排队/执行中的任务数（主线程读）。 */
    public int queuedCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** 负缓存大小 = 会话内确认不存在的区块数（主线程读）。 */
    public int absentCount() {
        return absent.size();
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
     * 精确层已退役（2026-09-02）：文件级现解现算 + 持久缓存已等效并优于精确层。
     */

    // ------------------------------------------------------------ load

    /** 后台线程：加载一个 chunk 的 16×16 地表颜色图（持久缓存优先，未命中解码+bake）。 */
    ChunkMapTile loadTile(File worldDir, int cx, int cz) {
        try {
            int rx = Math.floorDiv(cx, 32);
            int rz = Math.floorDiv(cz, 32);
            File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
            if (!region.isFile()) return null;
            RegionReader rr = new RegionReader(region);
            int index = (cz & 31) * 32 + (cx & 31);

            // 持久缓存（按 region 头时间戳失效）
            File gameDir = worldDir.getParentFile() == null ? null : worldDir.getParentFile().getParentFile();
            if (gameDir != null) {
                int ts = rr.getChunkTimestamp(index);
                if (ts != 0) {
                    File cf = MapTileCache.fileFor(gameDir, worldDir.getName(), cx, cz);
                    int[] cached = MapTileCache.load(cf, ts);
                    if (cached != null) {
                        ChunkMapTile t = new ChunkMapTile();
                        System.arraycopy(cached, 0, t.colors, 0, 256);
                        return t;
                    }
                }
            }

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

            // 生物群系（byte[256] 或 int[256]，JEID 环境可能是 int）——文件级层生物群系调色用
            int[] biomes = decodeBiomes(level);

            ChunkMapTile tile = buildTile(secStates, heightMap, biomes);

            // 写回持久缓存
            if (gameDir != null) {
                int ts = rr.getChunkTimestamp(index);
                if (ts != 0) {
                    MapTileCache.save(MapTileCache.fileFor(gameDir, worldDir.getName(), cx, cz), ts, tile.colors);
                }
            }
            return tile;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解码 chunk 的 Biomes → int[256]（每列群系 id）；不可用返回 null。 */
    private static int[] decodeBiomes(NbtNode level) {
        try {
            NbtNode bm = level.get("Biomes");
            if (bm == null) return null;
            if (bm.type == NbtNode.TAG_BYTE_ARRAY) {
                byte[] b = (byte[]) bm.value;
                if (b.length < 256) return null;
                int[] out = new int[256];
                for (int i = 0; i < 256; i++) out[i] = b[i] & 0xFF;
                return out;
            }
            if (bm.type == NbtNode.TAG_INT_ARRAY) {
                int[] v = (int[]) bm.value;
                return v.length >= 256 ? v : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 从内存 chunk NBT（如剪贴板）构建 tile（粘贴预览用，无需读盘）。
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
            return buildTile(secStates, heightMap, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 由 sections + HeightMap + 生物群系构建 16×16 颜色 tile。 */
    private ChunkMapTile buildTile(Map<Integer, int[]> secStates, int[] heightMap, int[] biomes) {
        ChunkMapTile tile = new ChunkMapTile();
        // 先算每列地表 y（供取色 + 坡度立体感）
        int[] ys = new int[256];
        for (int col = 0; col < 256; col++) ys[col] = findSurfaceY(secStates, heightMap, col);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int col = z * 16 + x;
                int y = ys[col];
                if (y < 0) {
                    tile.colors[col] = BACKGROUND;
                    continue;
                }
                int[] ids = secStates.get(y >> 4);
                int stateId = (ids != null) ? ids[((y & 15) << 8) | col] : 0;
                if (stateId == 0) {
                    tile.colors[col] = BACKGROUND;
                    continue;
                }
                // 文件级：MapColor 基础色 → 生物群系调色（草/叶/水）→ 高度+坡度立体感
                int c = colors.colorFor(stateId);
                if (biomes != null) {
                    int cat = colors.tintCategory(stateId >> 4);
                    if (cat != 0) c = MapColorCache.tint(c, colors.biomeColor(biomes[col], cat));
                }
                int hh = Math.max(0, Math.min(255, y));
                tile.colors[col] = MapColorCache.shadeRelief(c, hh,
                        ysNb(ys, col, +1, hh), ysNb(ys, col, -1, hh),
                        ysNb(ys, col, -16, hh), ysNb(ys, col, +16, hh));
            }
        }
        return tile;
    }

    /** 文件级 tile 邻列地表高（越界用自身，防 chunk 边缘缝隙）。 */
    private static int ysNb(int[] ys, int col, int off, int fallback) {
        int n = col + off;
        if (n < 0 || n >= 256) return fallback;
        int v = ys[n];
        return v < 0 ? fallback : v;
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
