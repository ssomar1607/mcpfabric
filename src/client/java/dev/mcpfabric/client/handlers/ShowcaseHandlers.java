package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.showcase.Recorder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Showcase / visual QA tools: camera perspective (F5), HUD (F1), field of view, joining a server with
 * its resource pack accepted up front, screenshots written to disk, and real-time video recording.
 */
public final class ShowcaseHandlers {
	private ShowcaseHandlers() {}

	public static void register(RpcRouter router) {
		router.register("view.setPerspective", ctx -> ClientMc.call(() -> {
			String mode = ctx.getString("mode").toLowerCase(Locale.ROOT);
			CameraType type = switch (mode) {
				case "first_person", "first" -> CameraType.FIRST_PERSON;
				case "third_person_back", "back" -> CameraType.THIRD_PERSON_BACK;
				case "third_person_front", "front" -> CameraType.THIRD_PERSON_FRONT;
				default -> throw RpcException.badRequest("mode must be first_person, third_person_back or third_person_front");
			};
			ClientMc.mc().options.setCameraType(type);
			return state(ClientMc.mc());
		}));

		router.register("view.setHud", ctx -> ClientMc.call(() -> {
			ClientMc.mc().options.hideGui = ctx.optBool("hidden", true);
			return state(ClientMc.mc());
		}));

		router.register("view.setChat", ctx -> ClientMc.call(() -> {
			// Hide the chat overlay locally (opacity 0) without telling the server, so command feedback and
			// chat events still arrive: first-person shots need the HUD (F1 also hides the hand) but no chat.
			boolean hidden = ctx.optBool("hidden", true);
			Minecraft mc = ClientMc.mc();
			mc.options.chatOpacity().set(hidden ? 0.0 : 1.0);
			mc.options.textBackgroundOpacity().set(hidden ? 0.0 : 0.5);
			return state(mc);
		}));

		router.register("view.setFov", ctx -> ClientMc.call(() -> {
			int fov = ctx.getInt("fov");
			if (fov < 30 || fov > 110) throw RpcException.badRequest("fov must be within 30..110");
			ClientMc.mc().options.fov().set(fov);
			return state(ClientMc.mc());
		}));

		router.register("view.clearToasts", ctx -> ClientMc.call(() -> {
			ClientMc.mc().getToastManager().clear();
			return state(ClientMc.mc());
		}));

		router.register("view.state", ctx -> ClientMc.call(() -> state(ClientMc.mc())));

		router.register("client.connect", ctx -> {
			String address = ctx.getString("address");
			boolean acceptPack = ctx.optBool("acceptResourcePack", true);
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				// While the startup resource reload is running a connection request is silently dropped.
				if (mc.getOverlay() != null) throw RpcException.unavailable("The client is still loading resources: retry in a few seconds.");
				if (mc.level != null) {
					mc.disconnect(new TitleScreen(), false);
				}
				ServerData data = new ServerData(ctx.optString("name", "mcpfabric"), address, ServerData.Type.OTHER);
				// The server's resource pack is what we want to see: accept it without the prompt screen.
				data.setResourcePackStatus(acceptPack ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.DISABLED);
				ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), data, false, null);
				return Json.ok("connecting to " + address + " (poll get_status until inWorld is true)");
			});
		});

		router.register("client.disconnect", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			if (mc.level == null) return Json.ok("not connected");
			mc.disconnect(new TitleScreen(), false);
			return Json.ok("disconnected");
		}));

		router.register("vision.screenshotToFile", ctx -> {
			if (!McpFabric.config().enableVision) {
				throw RpcException.unavailable("Vision is disabled in mcpfabric.config.json (enableVision=false).");
			}
			Path path = Path.of(ctx.getString("path"));
			Minecraft mc = ClientMc.mc();
			if (mc == null) throw RpcException.noClientPlayer();
			CompletableFuture<JsonObject> future = new CompletableFuture<>();
			mc.execute(() -> {
				try {
					Screenshot.takeScreenshot(mainRenderTarget(mc), image -> {
						try {
							Files.createDirectories(path.toAbsolutePath().getParent());
							image.writeToFile(path);
							JsonObject o = new JsonObject();
							o.addProperty("path", path.toAbsolutePath().toString());
							o.addProperty("width", image.getWidth());
							o.addProperty("height", image.getHeight());
							future.complete(o);
						} catch (Exception e) {
							future.completeExceptionally(e);
						} finally {
							image.close();
						}
					});
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});
			try {
				return future.get(Math.max(5000, McpFabric.config().callTimeoutMs), TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				throw new RpcException("screenshot_failed", "Failed to capture screenshot: " + cause.getMessage());
			}
		});

		router.register("record.start", ctx -> {
			if (!McpFabric.config().enableVision) {
				throw RpcException.unavailable("Vision is disabled in mcpfabric.config.json (enableVision=false).");
			}
			Path path = Path.of(ctx.getString("path"));
			int fps = ctx.optInt("fps", 60);
			String codec = ctx.optString("codec", "libx264");
			int quality = ctx.optInt("quality", codec.equals("h264_nvenc") ? 19 : 16);
			return ClientMc.call(() -> {
				try {
					Recorder.get().start(path, fps, codec, quality);
				} catch (Exception e) {
					throw new RpcException("record_failed", "Could not start recording: " + e.getMessage()
							+ " (is ffmpeg installed? ffmpegPath in mcpfabric.config.json)");
				}
				return statusJson(Recorder.get().status());
			});
		});

		router.register("record.stop", ctx -> {
			try {
				return statusJson(Recorder.get().stop());
			} catch (Exception e) {
				throw new RpcException("record_failed", "Could not stop recording: " + e.getMessage());
			}
		});

		router.register("record.status", ctx -> statusJson(Recorder.get().status()));
	}

	private static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("perspective", mc.options.getCameraType().name().toLowerCase(Locale.ROOT));
		o.addProperty("hudHidden", mc.options.hideGui);
		o.addProperty("fov", mc.options.fov().get());
		o.addProperty("chatHidden", mc.options.chatOpacity().get() == 0.0);
		o.addProperty("width", mc.getWindow().getWidth());
		o.addProperty("height", mc.getWindow().getHeight());
		o.addProperty("inWorld", mc.level != null);
		// true while a resource reload (e.g. the server pack) covers the screen: wait before capturing
		o.addProperty("loading", mc.getOverlay() != null);
		o.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
		o.addProperty("ready", mc.level != null && mc.getOverlay() == null && mc.screen == null);
		return o;
	}

	private static JsonObject statusJson(Recorder.Status s) {
		return Json.GSON.toJsonTree(s).getAsJsonObject();
	}

	private static RenderTarget mainRenderTarget(Minecraft mc) {
		//? if <26.2 {
		return mc.getMainRenderTarget();
		//?} else
		/*return mc.gameRenderer.mainRenderTarget();*/
	}
}
