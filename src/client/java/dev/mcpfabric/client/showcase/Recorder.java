package dev.mcpfabric.client.showcase;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time video capture of the game window, for showcase videos.
 *
 * <p>Every rendered frame is read back asynchronously from the main framebuffer (the vanilla
 * screenshot path, so the image is exactly what the player sees) and kept as "latest frame". A
 * writer thread feeds ffmpeg at a fixed frame rate, paced by the wall clock: the video length always
 * matches real time, frames are duplicated when the game renders slower than the target rate and
 * skipped when it renders faster. Anything driven by the real clock (item animations using
 * {@code local_time}) therefore plays at its true speed in the video.
 */
public final class Recorder {
	private static final Recorder INSTANCE = new Recorder();

	public static Recorder get() {
		return INSTANCE;
	}

	private volatile boolean recording;
	private volatile boolean readbackPending;
	private final AtomicReference<byte[]> latest = new AtomicReference<>();
	private Process ffmpeg;
	private Thread writer;
	private int width, height, fps;
	private Path output;
	private long startNanos;
	private volatile long framesWritten;
	private volatile String lastError;

	private Recorder() {}

	public synchronized void start(Path out, int fps, String codec, int quality) throws IOException {
		if (recording) throw new IOException("Already recording to " + output);
		Minecraft mc = Minecraft.getInstance();
		RenderTarget target = mainRenderTarget(mc);
		this.width = target.width;
		this.height = target.height;
		this.fps = fps;
		this.output = out;
		Files.createDirectories(out.toAbsolutePath().getParent());

		List<String> cmd = new ArrayList<>(List.of(McpFabric.config().ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
				"-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-"));
		if (codec.equals("h264_nvenc")) {
			cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p6", "-rc", "vbr", "-cq", String.valueOf(quality), "-b:v", "0"));
		} else {
			cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", String.valueOf(quality)));
		}
		cmd.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toAbsolutePath().toString()));
		ffmpeg = new ProcessBuilder(cmd).redirectErrorStream(true)
				.redirectOutput(out.toAbsolutePath().resolveSibling(out.getFileName() + ".ffmpeg.log").toFile()).start();

		latest.set(null);
		framesWritten = 0;
		lastError = null;
		startNanos = System.nanoTime();
		recording = true;
		writer = new Thread(this::writeLoop, "mcpfabric-recorder");
		writer.setDaemon(true);
		writer.start();
		McpFabric.LOGGER.info("[mcpfabric] recording {}x{} @{} fps -> {}", width, height, fps, out);
	}

	/** Stops the capture and waits for ffmpeg to finalize the file. Returns a status summary. */
	public synchronized Status stop() throws IOException, InterruptedException {
		if (!recording) throw new IOException("Not recording.");
		recording = false;
		writer.join(5000);
		ffmpeg.getOutputStream().close();
		boolean done = ffmpeg.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
		Status s = status();
		ffmpeg = null;
		if (!done) throw new IOException("ffmpeg did not finish within 60 s");
		return s;
	}

	public Status status() {
		double seconds = recording ? (System.nanoTime() - startNanos) / 1e9 : framesWritten / (double) Math.max(1, fps);
		return new Status(recording, output == null ? null : output.toAbsolutePath().toString(), width, height, fps, framesWritten, seconds, lastError);
	}

	public record Status(boolean recording, String path, int width, int height, int fps, long frames, double seconds, String error) {}

	/** Called at the end of every rendered frame (render thread, see MinecraftFrameMixin). */
	public void onFrameRendered(Minecraft mc) {
		if (!recording || readbackPending) return;
		RenderTarget target = mainRenderTarget(mc);
		if (target.width != width || target.height != height) {
			lastError = "window resized during recording (" + target.width + "x" + target.height + "), frames skipped";
			return;
		}
		readbackPending = true;
		try {
			Screenshot.takeScreenshot(target, image -> {
				try {
					latest.set(toRgba(image));
				} finally {
					image.close();
					readbackPending = false;
				}
			});
		} catch (Throwable t) {
			readbackPending = false;
			lastError = t.toString();
		}
	}

	private static byte[] toRgba(NativeImage image) {
		int[] abgr = image.getPixelsABGR();          // little-endian ABGR int == R,G,B,A bytes
		ByteBuffer buf = ByteBuffer.allocate(abgr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		buf.asIntBuffer().put(abgr);
		return buf.array();
	}

	private void writeLoop() {
		long frameNanos = 1_000_000_000L / fps;
		long next = System.nanoTime();
		byte[] last = null;
		try (OutputStream os = ffmpeg.getOutputStream()) {
			while (recording) {
				long now = System.nanoTime();
				if (now < next) {
					Thread.sleep(Math.max(0, (next - now) / 1_000_000), (int) ((next - now) % 1_000_000));
					continue;
				}
				byte[] f = latest.get();
				if (f != null) last = f;
				if (last != null) {
					os.write(last);
					framesWritten++;
				}
				next += frameNanos;
				// fell far behind (e.g. the game froze): resync instead of bursting duplicates
				if (System.nanoTime() - next > 1_000_000_000L) next = System.nanoTime();
			}
		} catch (Exception e) {
			lastError = "writer: " + e;
			recording = false;
		}
	}

	private static RenderTarget mainRenderTarget(Minecraft mc) {
		//? if <26.2 {
		return mc.getMainRenderTarget();
		//?} else
		/*return mc.gameRenderer.mainRenderTarget();*/
	}
}
