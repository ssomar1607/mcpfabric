package dev.mcpfabric.client.mixin;

import dev.mcpfabric.client.showcase.Recorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** End of each rendered frame: hands the finished framebuffer to the recorder (no-op when idle). */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void mcpfabric$onFrame(boolean advanceGameTime, CallbackInfo ci) {
		Recorder.get().onFrameRendered((Minecraft) (Object) this);
	}
}
