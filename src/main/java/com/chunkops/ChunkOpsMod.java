package com.chunkops;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * ChunkOps112 - 1.12.2 模组兼容区块编辑器（阶段 0 骨架）
 */
@Mod(modid = ChunkOpsMod.MODID, name = ChunkOpsMod.NAME, version = ChunkOpsMod.VERSION)
public class ChunkOpsMod {

    public static final String MODID = "chunkops";
    public static final String NAME = "ChunkOps 1.12.2";
    public static final String VERSION = "0.1.0";

    private static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("ChunkOps preInit: core loaded");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        logger.info("ChunkOps init");
    }
}
