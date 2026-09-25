package dev.mcpfabric.client.mixin;

import dev.mcpfabric.client.showcase.CameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the cinematic camera pose after vanilla computed its own (no-op while the controller is off). */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow private boolean detached;

	@Shadow protected abstract void setRotation(float yRot, float xRot);

	@Shadow protected abstract void setPosition(Vec3 pos);

	@Inject(method = "setup", at = @At("TAIL"))
	private void mcpfabric$cinematic(Level level, Entity entity, boolean detachedIn, boolean mirrored, float partialTick, CallbackInfo ci) {
		double[] p = CameraController.get().pose(Minecraft.getInstance(), partialTick);
		if (p == null) return;
		this.detached = true;                       // render the player's body, hide the first-person hand
		setRotation((float) p[3], (float) p[4]);
		setPosition(new Vec3(p[0], p[1], p[2]));
	}
}
