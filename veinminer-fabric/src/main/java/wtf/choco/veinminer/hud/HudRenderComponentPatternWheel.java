package wtf.choco.veinminer.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.VeinMinerMod;
import wtf.choco.veinminer.config.ClientConfig;
import wtf.choco.veinminer.network.FabricServerState;

/**
 * A {@link HudRenderComponent} for the pattern selection wheel in the top left.
 */
public final class HudRenderComponentPatternWheel implements HudRenderComponent {

    private static final float STAY_TIME_MS = 3000F;
    private static final float FADE_MS = 200F;
    private static final float TOTAL_TIME_MS = STAY_TIME_MS + (FADE_MS * 2);

    private float remainingMs = -1L;

    @Override
    public void render(@NotNull Minecraft client, @NotNull GuiGraphics graphics, float tickDelta) {
        client.getProfiler().push("veinminerPatternWheel");

        PoseStack stack = graphics.pose();
        stack.pushPose();
        stack.translate(4, 0, 0);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Calculate alpha based on progress of fade in/out times
        float alphaProgress = 1.0F;
        if (remainingMs >= (STAY_TIME_MS + FADE_MS)) { // Fade in
            alphaProgress = 1.0F - (remainingMs - (STAY_TIME_MS + FADE_MS)) / FADE_MS;
        }
        else if (remainingMs < FADE_MS) { // Fade out
            alphaProgress = (remainingMs / FADE_MS);
        }

        alphaProgress = Mth.clamp(alphaProgress, 0.0F, 1.0F);

        // Final colour with alpha included
        int alpha = (Mth.floor(alphaProgress * 255) << 24) & 0xFF000000;
        int colour = 0xFFFFFF | alpha;

        FabricServerState serverState = VeinMinerMod.getServerState();
        String before = serverState.getPreviousPattern().toString();
        String selected = serverState.getSelectedPattern().toString();
        String after = serverState.getNextPattern().toString();

        graphics.drawString(client.font, Component.literal(selected), 0, client.font.lineHeight, colour);

        stack.pushPose();
        stack.translate(0, 3, 0);
        stack.scale(0.6F, 0.6F, 0.6F);
        graphics.drawString(client.font, Component.literal(before), 0, 0, colour);
        graphics.drawString(client.font, Component.literal(after), 0, client.font.lineHeight * 3, colour);
        stack.popPose();

        RenderSystem.disableBlend();

        stack.popPose();

        this.remainingMs -= (client.getDeltaFrameTime() * 50);

        client.getProfiler().pop();
    }

    @Override
    public boolean shouldRender(@NotNull ClientConfig config) {
        return config.isAllowPatternSwitchingKeybind() && VeinMinerMod.getServerState().hasPatternKeys() && remainingMs >= 0L;
    }

    /**
     * Push a render cycle to the component.
     * <p>
     * If this wheel is not being rendered, it will start rendering. If it is fading it, it will
     * continue to fade in. If it is during its stay period, it will reset to the start of the stay
     * period. If it is fading out, it will start fading in starting from the current fade out time.
     */
    public void pushRender() {
        if (remainingMs <= 0) { // If not rendered, set to max time
            this.remainingMs = TOTAL_TIME_MS;
        }
        else if (remainingMs < FADE_MS) { // If fading out, fade back in starting at current fade out point
            this.remainingMs = TOTAL_TIME_MS - remainingMs;
        }
        else if (remainingMs < (TOTAL_TIME_MS - FADE_MS)) { // If already faded in (during stay time), skip fade in time
            this.remainingMs = TOTAL_TIME_MS - FADE_MS;
        }
    }

}
