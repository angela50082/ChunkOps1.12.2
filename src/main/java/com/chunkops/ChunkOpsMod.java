package com.chunkops;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * ChunkOps112 - 1.12.2 模组兼容区块编辑器（阶段 0 骨架）
 *
 * 入口（2026-09-15）：主菜单注入按钮 + 模组列表「配置」（guiFactory）——后者不受主菜单美化模组
 * （FancyMenu/CustomMainMenu 等会隐藏/清除主菜单按钮）影响，是可靠备用入口。
 * 显示名以 "! " 开头：Forge 模组列表按 name.toLowerCase() 排序（SortType.NORMAL/A_TO_Z），
 * 故本模组恒排最前，便于查找。
 *
 * 显示名固定为英文（2026-09-15）：1.12.2 的 @Mod(name) / mcmod.info 不支持语言键，
 * 写死中文会让英文玩家在模组列表里无法辨认；界面文案则全部走 assets/chunkops/lang/。
 */
@Mod(modid = ChunkOpsMod.MODID, name = ChunkOpsMod.NAME, version = ChunkOpsMod.VERSION,
        guiFactory = "com.chunkops.gui.ChunkOpsGuiFactory")
public class ChunkOpsMod {

    public static final String MODID = "chunkops";
    public static final String NAME = "! ChunkOps";
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
    }
}
