package com.ovo.lastsongofelysian.client;

import com.ovo.lastsongofelysian.client.mesh.MeshDebug;
import com.ovo.lastsongofelysian.lastsongofelysian;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * F9 —— 循环切换三角网格渲染的调试模式（正常 / 全白 / 线框 / 法线）。
 *
 * <p>用来把「数据问题」和「渲染管线问题」分开：全白仍花说明不是贴图；线框能看三角形连接；
 * 法线能看朝向是否一致。
 */
@Mod.EventBusSubscriber(
        modid = lastsongofelysian.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class MeshDebugKeyMapping {

    public static final KeyMapping CYCLE = new KeyMapping(
            "key.lastsongofelysian.mesh_debug",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.lastsongofelysian"
    );

    private MeshDebugKeyMapping() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(CYCLE);
    }

    /** 客户端 tick 里检测按下。 */
    @Mod.EventBusSubscriber(
            modid = lastsongofelysian.MODID,
            value = Dist.CLIENT
    )
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (CYCLE.consumeClick()) {
                MeshDebug.Mode m = MeshDebug.cycle();
                if (net.minecraft.client.Minecraft.getInstance().player != null) {
                    net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("[Debug] 网格渲染模式: " + m.label), true);
                }
            }
        }
    }
}
