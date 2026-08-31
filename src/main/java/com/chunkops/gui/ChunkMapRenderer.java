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
 * - 取色：MapColorCache（IBakedModel 顶面纹理中心像素 + 高度明暗）
 * - 异步：线程池加载 chunk → 主线程 pump 收割 → DynamicTexture 缓存
 * - LRU：tile 缓存与纹理缓存均有上限（自动淘汰最旧）
 */
public class ChunkMapRenderer {

    private static final int TILE_CACHE_MAX = 1024;
    private static final int TEXTURE_CACHE_MAX = 1024;
    private static final int BACKGROUND = 0xFF14181C;
    private static final int PENDING = 0xFF20262C;

    private final MapColorCache colors = new MapColorCache();

    private final Map<Long, ChunkMapTile> tiles = new LinkedHashMap<Long, ChunkMapTile>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkMapTile> eldest) {
            return size() > TILE_CACHE_MAX;
        }
    };
    private final Map<Long, ResourceLocation> textures = new LinkedHashMap<Long, ResourceLocation>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, ResourceLocation> eldest) {
            return size() > TEXTURE_CACHE_MAX;
        }
    };
    private final Map<Long, Future<ChunkMapTile>> pending = new HashMap<Long, Future<ChunkMapTile>>();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
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

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * 主线程调用：收割已完成的异步加载，返回 tile（未就绪返回 null 并提交加载）。
     */
    public ChunkMapTile getOrLoad(File worldDir, int cx, int cz) {
        pump();
        long k = key(cx, cz);
        ChunkMapTile tile = tiles.get(k);
        if (tile != null) return tile;
        synchronized (pending) {
            if (!pending.containsKey(k)) {
                final int fx = cx;
                final int fz = cz;
                pending.put(k, pool.submit(new java.util.concurrent.Callable<ChunkMapTile>() {
                    public ChunkMapTile call() {
                        return loadTile(worldDir, fx, fz);
                    }
                }));
            }
        }
        return null;
    }

    /** 主线程调用：收割完成的任务。 */
    public void pump() {
        synchronized (pending) {
            Iterator<Map.Entry<Long, Future<ChunkMapTile>>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Future<ChunkMapTile>> e = it.next();
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
    public ResourceLocation textureFor(long key, ChunkMapTile tile, TextureManager tm) {
        ResourceLocation loc = textures.get(key);
        if (loc == null) {
            loc = new ResourceLocation("chunkops", "map/" + key);
            DynamicTexture dt = new DynamicTexture(16, 16);
            int[] data = dt.getTextureData();
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

    public int background() {
        return BACKGROUND;
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

            ChunkMapTile tile = new ChunkMapTile();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int col = z * 16 + x;
                    int y = heightMap != null ? heightMap[col] : scanHeight(secStates, col);
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
        } catch (Exception e) {
            return null;
        }
    }

    /** 无 HeightMap 时从 y=255 向下扫描地表。 */
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
