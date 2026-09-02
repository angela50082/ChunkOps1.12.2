package com.chunkops;

import com.chunkops.exact.ExactMapCollector;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * ChunkOps112 - 1.12.2 模组兼容区块编辑器（阶段 0 骨架）
 */
@Mod(modid = ChunkOpsMod.MODID, name = ChunkOpsMod.NAME, version = ChunkOpsMod.VERSION)
public class ChunkOpsMod {

    public static final String MODID = "chunkops";
    public static final String NAME = "ChunkOps 1.12.2";
    public static final String VERSION = "0.1.0";

    private static Logger logger;

    /** 精确数据层采集器（P2）：生命周期与客户端一致。 */
    private static ExactMapCollector exactCollector;

    /** 编辑器预取接入点（null=未注册）。 */
    public static ExactMapCollector getExactCollector() {
        return exactCollector;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("ChunkOps preInit: core loaded");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        logger.info("ChunkOps init");
        // 导出注册表快照（阶段 1）：输出到 .minecraft/registry-snapshot.json
        try {
            File mcDir = Loader.instance().getConfigDir().getParentFile();
            ChunkOpsExport.export(mcDir);
            // 诊断（REID 存储表寻址）：多种取值途径对比，定位与存档 palette 项一致的来源
            try {
                net.minecraft.block.Block bf = net.minecraft.block.Block.getBlockFromName(
                        "forestry:fences.vanilla.fireproof.0");
                if (bf != null) {
                    net.minecraft.block.state.IBlockState st = bf.getDefaultState();
                    int rid = net.minecraft.block.Block.getIdFromBlock(bf);
                    int regId = net.minecraft.block.Block.REGISTRY.getIDForObject(bf);
                    int meta = bf.getMetaFromState(st);
                    int gsid = net.minecraft.block.Block.getStateId(st);
                    int storage = ChunkOpsExport.storageStateId(st);
                    logger.info("[ChunkOps-diag] forestry:fences.vanilla.fireproof.0  "
                            + "getIdFromBlock={} REGISTRY={} meta={} getStateId={} storageStateId={}",
                            rid, regId, meta, gsid, storage);
                } else {
                    logger.info("[ChunkOps-diag] forestry:fences.vanilla.fireproof.0 未找到");
                }
            } catch (Exception ie) {
                logger.info("[ChunkOps-diag] 诊断失败: " + ie.getMessage());
            }
        } catch (Exception e) {
            logger.error("registry-snapshot 导出失败", e);
        }
        // 精确数据层采集器（P2）：ChunkEvent.Load（Forge bus）+ ClientTick 兜底（FML bus）
        try {
            exactCollector = new ExactMapCollector();
            MinecraftForge.EVENT_BUS.register(exactCollector);
            FMLCommonHandler.instance().bus().register(exactCollector);
            logger.info("ExactMapCollector registered");
        } catch (Exception e) {
            logger.error("ExactMapCollector 注册失败", e);
        }
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        if (exactCollector != null) {
            exactCollector.shutdown();
            exactCollector = null;
        }
    }
}
