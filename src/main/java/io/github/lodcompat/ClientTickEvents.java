package io.github.lodcompat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

@Environment(EnvType.CLIENT)
public class ClientTickEvents {
    private static boolean initialized = false;

    public static void register() {
        if (initialized) return;
        initialized = true;

        // Register end tick event (setelah render sudah siap)
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null && client.player != null) {
                // Update LOD renderer dengan posisi player
                LodRenderSystem.updateCamera(
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ()
                );
            }
        });

        // Register render event
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST.register(context -> {
            if (MinecraftClient.getInstance().world != null) {
                LodRenderSystem.render(context.matrixStack(), context.camera());
            }
        });
    }
}
