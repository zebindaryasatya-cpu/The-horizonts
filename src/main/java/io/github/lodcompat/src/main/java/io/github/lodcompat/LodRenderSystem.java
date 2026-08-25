package io.github.lodcompat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Camera;

@Environment(EnvType.CLIENT)
public class LodRenderSystem {
    private static InstancedLodRenderer renderer;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        
        RenderSystem.assertOnRenderThreadOrInit();
        
        try {
            renderer = new InstancedLodRenderer();
            renderer.initialize();
            LodCompatClient.LOGGER.info("LOD Render System initialized successfully");
            initialized = true;
        } catch (Exception e) {
            LodCompatClient.LOGGER.error("Failed to initialize LOD Render System", e);
        }
    }

    public static void updateCamera(double x, double y, double z) {
        if (renderer != null) {
            renderer.updatePlayerPosition(x, y, z);
        }
    }

    public static void render(com.mojang.blaze3d.vertex.PoseStack matrixStack, Camera camera) {
        if (renderer == null || !initialized) return;
        
        RenderSystem.assertOnRenderThread();
        
        try {
            renderer.render(camera);
        } catch (Exception e) {
            LodCompatClient.LOGGER.error("Error rendering LOD", e);
        }
    }

    public static void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
        }
    }
}
