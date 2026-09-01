package com.chunkops.exact;

import com.chunkops.ChunkOpsMod;
import com.chunkops.core.ExactMapFile;
import com.chunkops.gui.MapColorCache;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 精确数据层采集器（设计文档 §6.6，P2）：
 * 游戏内（单人世界）玩家探索时，对已加载 chunk 的每列取
 * 「地表方块 MapColor × BlockColors 生物群系调色 + 高度明暗 + 方块名 + 群系名」，
 * 后台线程增量写入 COPM .dat（gameDir/chunkops/map/&lt;世界名&gt;/&lt;维度&gt;/r.rx.rz.dat）。
 *
 * 触发双路径：
 * 1. ChunkEvent.Load（即时，客户端世界）；
 * 2. ClientTickEvent 每 40 tick 扫描玩家周围已加载 chunk（兜底，不依赖事件是否触发）。
 * 采集线程 2 个（daemon），只读 world（chunk 数组访问），不卡主线程。
 */
public class ExactMapCollector {

    private static final Logger LOG = LogManager.getLogger(ChunkOpsMod.MODID);

    private static final int TICK_INTERVAL = 40;
    private static final int MAX_QUEUE = 4096;
    /** 防重集合上限（超限清空，重采为幂等覆盖写）。 */
    private static final int COLLECTED_MAX = 200000;

    private final ConcurrentHashMap<Long, Boolean> collected = new ConcurrentHashMap<Long, Boolean>();
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<Task>();
    private final ExecutorService workers;
    private final AtomicInteger tickCounter = new AtomicInteger();
    /** chunkKey(dim,cx,cz) → 对应 region 文件的锁（防止并发合并写坏）。 */
    private final ConcurrentHashMap<String, Object> regionLocks = new ConcurrentHashMap<String, Object>();

    public ExactMapCollector() {
        workers = Executors.newFixedThreadPool(2, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "chunkops-exact");
                t.setDaemon(true);
                return t;
            }
        });
        for (int i = 0; i < 2; i++) {
            workers.submit(new Worker());
        }
    }

    public void shutdown() {
        workers.shutdownNow();
    }

    // ------------------------------------------------------------ 事件

    /** 即时路径：chunk 加载（仅客户端 world，单人/多人客户端世界统一入队，采集时再过滤单机）。 */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        World w = event.getWorld();
        if (!(w instanceof WorldClient)) return;
        offer((WorldClient) w, event.getChunk().x, event.getChunk().z);
    }

    /** 兜底路径：客户端 tick 扫描玩家周围已加载 chunk。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if ((tickCounter.incrementAndGet() % TICK_INTERVAL) != 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;
        WorldClient world = mc.world;
        if (mc.getIntegratedServer() == null) return; // 仅单人世界（服务器存档在远端，采集无意义）
        int cx = mc.player.chunkCoordX;
        int cz = mc.player.chunkCoordZ;
        int r = 8 + Math.min(8, mc.gameSettings.renderDistanceChunks); // 玩家常到范围的一圈
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int wx = cx + dx;
                int wz = cz + dz;
                long key = chunkKey(world.provider.getDimension(), wx, wz);
                if (collected.containsKey(key)) continue;
                try {
                    net.minecraft.world.chunk.IChunkProvider prov = world.getChunkProvider();
                    if (prov != null && prov.getLoadedChunk(wx, wz) != null) offer(world, wx, wz);
                } catch (Exception ignored) {
                    // 世界状态切换瞬间：跳过
                }
            }
        }
    }

    // ------------------------------------------------------------ 入队

    private void offer(WorldClient world, int cx, int cz) {
        long key = chunkKey(world.provider.getDimension(), cx, cz);
        if (collected.putIfAbsent(key, Boolean.TRUE) != null) return;
        if (collected.size() > COLLECTED_MAX) collected.clear();
        if (!queue.offer(new Task(world, cx, cz))) {
            collected.remove(key); // 队列满：允许下次重试
        }
    }

    private static long chunkKey(int dim, int cx, int cz) {
        return ((long) (dim & 0xFFFF) << 48) | ((long) (cx & 0xFFFFFF) << 24) | (cz & 0xFFFFFF);
    }

    // ------------------------------------------------------------ 采集

    private static class Task {
        final WorldClient world;
        final int cx, cz;

        Task(WorldClient world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }
    }

    private class Worker implements Runnable {
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    Task t = queue.take();
                    try {
                        collect(t.world, t.cx, t.cz);
                    } catch (Exception e) {
                        LOG.debug("exact collect failed at " + t.cx + "," + t.cz + ": " + e.getMessage());
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ 核心

    /** 采集一个 chunk：扫描每列地表 → 取色/取名 → 合并写回 region 的 .dat。 */
    void collect(World world, int cx, int cz) {
        if (world == null) return;
        try {
            if (Minecraft.getMinecraft().getIntegratedServer() == null) return; // 仅单人世界
            Chunk chunk = world.getChunk(cx, cz);
            if (chunk == null || !chunk.isLoaded()) return;
            int rx = Math.floorDiv(cx, 32);
            int rz = Math.floorDiv(cz, 32);
            File file = dataFile(world, rx, rz);

            Object lock = regionLocks.get(file.getAbsolutePath());
            if (lock == null) {
                Object n = new Object();
                Object old = regionLocks.putIfAbsent(file.getAbsolutePath(), n);
                lock = old != null ? old : n;
            }
            synchronized (lock) {
                ExactMapFile.ExactRegion reg = null;
                try {
                    reg = ExactMapFile.read(file);
                } catch (Exception e) {
                    LOG.warn("COPM 读取失败（重建）: " + file + " : " + e.getMessage());
                }
                if (reg == null) reg = new ExactMapFile.ExactRegion(256, 256);

                int base = (cx & 31) * 16;
                int baseZ = (cz & 31) * 16;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int sy = findSurface(chunk, x, z);
                        int gi = (baseZ + z) * 256 + (base + x);
                        if (sy < 0) {
                            // 虚空/整列空气：空白约定
                            reg.color[gi] = 0;
                            reg.heightY[gi] = 0;
                            reg.blockIdx[gi] = 0;
                            reg.biomeIdx[gi] = 0;
                            continue;
                        }
                        BlockPos pos = new BlockPos(cx * 16 + x, sy, cz * 16 + z);
                        IBlockState st = chunk.getBlockState(x, sy, z);
                        reg.heightY[gi] = (byte) sy;
                        reg.color[gi] = sampleColor(world, st, pos, sy);
                        reg.blockIdx[gi] = blockIndex(reg, st);
                        reg.biomeIdx[gi] = biomeIndex(reg, world, pos);
                    }
                }
                ExactMapFile.write(file, reg);
            }
        } catch (Exception e) {
            LOG.debug("exact collect exception at " + cx + "," + cz + ": " + e.getMessage());
        }
    }

    private static File dataFile(World world, int rx, int rz) {
        File gameDir = Minecraft.getMinecraft().gameDir;
        String worldName = "world";
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getIntegratedServer() != null) {
                WorldServer sw = mc.getIntegratedServer().getWorld(world.provider.getDimension());
                if (sw != null && sw.getSaveHandler().getWorldDirectory() != null) {
                    worldName = sw.getSaveHandler().getWorldDirectory().getName();
                }
            }
        } catch (Exception ignored) {
            // 世界目录不可得时用 world
        }
        File dimDir;
        try {
            dimDir = new File(new File(new File(gameDir, "chunkops/map"), worldName),
                    String.valueOf(world.provider.getDimension()));
        } catch (Exception e) {
            dimDir = new File(new File(new File(gameDir, "chunkops/map"), worldName), "0");
        }
        return new File(dimDir, ExactMapFile.fileName(rx, rz));
    }

    /** 从 y=255 向下扫描第一个非空气方块的 y（地表）。 */
    static int findSurface(Chunk chunk, int x, int z) {
        for (int y = 255; y >= 0; y--) {
            if (chunk.getBlockState(x, y, z).getBlock() != Blocks.AIR) return y;
        }
        return -1;
    }

    /** Xaero 式取色栈：MapColor 基础色 × BlockColors 生物群系调色 + 高度明暗。 */
    static int sampleColor(World world, IBlockState st, BlockPos pos, int y) {
        int base = 0x888888;
        try {
            base = st.getMapColor(world, pos).colorValue;
        } catch (Exception ignored) {
            // 部分方块 getMapColor 异常 → 兜底色
        }
        int tint = 0xFFFFFF;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getBlockColors() != null) {
                tint = mc.getBlockColors().colorMultiplier(st, world, pos, 0);
            }
        } catch (Exception ignored) {
            // 调色异常 → 不调色
        }
        int r = ((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((base >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (base & 0xFF) * (tint & 0xFF) / 255;
        int c = 0xFF000000 | (r << 16) | (g << 8) | b;
        return MapColorCache.shade(c, Math.max(0, Math.min(255, y)));
    }

    /** 方块名字典索引（"modid:block#meta"；空气=0）。 */
    private static short blockIndex(ExactMapFile.ExactRegion reg, IBlockState st) {
        Block block = st.getBlock();
        if (block == Blocks.AIR) return 0;
        String name;
        try {
            net.minecraft.util.ResourceLocation rl = Block.REGISTRY.getNameForObject(block);
            if (rl == null) return 0;
            name = rl.toString();
            int meta = block.getMetaFromState(st);
            name = name + "#" + meta;
        } catch (Exception e) {
            return 0;
        }
        int idx = reg.blockNames.indexOf(name);
        if (idx >= 0) return (short) idx;
        if (reg.blockNames.size() >= 65535) return 0; // 理论不会到
        reg.blockNames.add(name);
        return (short) (reg.blockNames.size() - 1);
    }

    /** 生物群系名字典索引（未知=0）。 */
    private static byte biomeIndex(ExactMapFile.ExactRegion reg, World world, BlockPos pos) {
        Biome biome = null;
        try {
            biome = world.getBiome(pos);
        } catch (Exception ignored) {
        }
        String name = null;
        if (biome != null) {
            try {
                net.minecraft.util.ResourceLocation rl = Biome.REGISTRY.getNameForObject(biome);
                if (rl != null) name = rl.toString();
            } catch (Exception ignored) {
            }
        }
        if (name == null) return 0;
        int idx = reg.biomeNames.indexOf(name);
        if (idx >= 0) return (byte) idx;
        if (reg.biomeNames.size() >= 255) return 0;
        reg.biomeNames.add(name);
        return (byte) (reg.biomeNames.size() - 1);
    }
}
