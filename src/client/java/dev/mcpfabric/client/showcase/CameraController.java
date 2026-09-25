package dev.mcpfabric.client.showcase;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Cinematic camera for showcase videos: overrides the camera pose every frame (see CameraMixin) while the player
 * keeps being simulated and rendered (the camera is flagged detached, so the player's body is drawn).
 *
 * <p>Modes: FIXED (one pose), PATH (keyframes: Catmull-Rom positions, eased timing, yaw/pitch or look-at per
 * key) and ORBIT (around a point or the player). Time is the wall clock, like the recorder, so a camera move
 * recorded at any frame rate lasts exactly its duration.
 */
public final class CameraController {
	private static final CameraController INSTANCE = new CameraController();

	public static CameraController get() {
		return INSTANCE;
	}

	public enum Mode { OFF, FIXED, PATH, ORBIT }

	/** A camera key. lookAt, when set, overrides yaw/pitch. t = seconds from the start of the path. */
	public record Key(double t, Vec3 pos, float yaw, float pitch, Vec3 lookAt) {}

	private volatile Mode mode = Mode.OFF;
	private volatile List<Key> keys = List.of();
	private volatile boolean loop;
	private volatile long startNanos;
	// orbit
	private volatile Vec3 center;          // null = follow the player
	private volatile double centerYOffset, radius, height, startDeg, degPerSec;

	private CameraController() {}

	public Mode mode() {
		return mode;
	}

	public void off() {
		mode = Mode.OFF;
	}

	public void fixed(Key k) {
		keys = List.of(k);
		startNanos = System.nanoTime();
		mode = Mode.FIXED;
	}

	public void path(List<Key> k, boolean loop) {
		List<Key> sorted = new ArrayList<>(k);
		sorted.sort((a, b) -> Double.compare(a.t(), b.t()));
		keys = List.copyOf(sorted);
		this.loop = loop;
		startNanos = System.nanoTime();
		mode = Mode.PATH;
	}

	public void orbit(Vec3 center, double centerYOffset, double radius, double height, double startDeg, double degPerSec) {
		this.center = center;
		this.centerYOffset = centerYOffset;
		this.radius = radius;
		this.height = height;
		this.startDeg = startDeg;
		this.degPerSec = degPerSec;
		startNanos = System.nanoTime();
		mode = Mode.ORBIT;
	}

	public double elapsed() {
		return (System.nanoTime() - startNanos) / 1e9;
	}

	/** Duration of the current path (0 for other modes). */
	public double duration() {
		List<Key> k = keys;
		return mode == Mode.PATH && !k.isEmpty() ? k.get(k.size() - 1).t() : 0;
	}

	/** Pose for this frame: {x, y, z, yaw, pitch}, or null when the controller is off. */
	public double[] pose(Minecraft mc, float partialTick) {
		switch (mode) {
			case FIXED: {
				Key k = keys.get(0);
				return withLook(k.pos(), k.yaw(), k.pitch(), k.lookAt());
			}
			case ORBIT: {
				Vec3 c = center;
				if (c == null) {
					Entity e = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
					if (e == null) return null;
					c = e.getPosition(partialTick);
				}
				c = c.add(0, centerYOffset, 0);
				double a = Math.toRadians(startDeg + degPerSec * elapsed());
				Vec3 p = new Vec3(c.x + Math.sin(a) * radius, c.y + height, c.z - Math.cos(a) * radius);
				return withLook(p, 0, 0, c);
			}
			case PATH: {
				List<Key> k = keys;
				if (k.isEmpty()) return null;
				double t = elapsed();
				double end = k.get(k.size() - 1).t();
				if (loop && end > 0) t = t % end;
				if (t <= k.get(0).t() || k.size() == 1) return withLook(k.get(0).pos(), k.get(0).yaw(), k.get(0).pitch(), k.get(0).lookAt());
				if (t >= end) {
					Key l = k.get(k.size() - 1);
					return withLook(l.pos(), l.yaw(), l.pitch(), l.lookAt());
				}
				int i = 0;
				while (i < k.size() - 2 && t > k.get(i + 1).t()) i++;
				Key a = k.get(i), b = k.get(i + 1);
				double u = (t - a.t()) / Math.max(1e-6, b.t() - a.t());
				u = u * u * (3 - 2 * u);                                   // ease in/out between keys
				Vec3 p0 = k.get(Math.max(0, i - 1)).pos(), p3 = k.get(Math.min(k.size() - 1, i + 2)).pos();
				Vec3 p = catmullRom(p0, a.pos(), b.pos(), p3, u);
				Vec3 look = a.lookAt() != null && b.lookAt() != null ? a.lookAt().lerp(b.lookAt(), u) : null;
				float yaw = (float) (a.yaw() + wrap(b.yaw() - a.yaw()) * u);
				float pitch = (float) (a.pitch() + (b.pitch() - a.pitch()) * u);
				return withLook(p, yaw, pitch, look);
			}
			default:
				return null;
		}
	}

	private static double[] withLook(Vec3 p, float yaw, float pitch, Vec3 lookAt) {
		if (lookAt != null) {
			double dx = lookAt.x - p.x, dy = lookAt.y - p.y, dz = lookAt.z - p.z;
			yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		}
		return new double[]{p.x, p.y, p.z, yaw, pitch};
	}

	private static double wrap(double d) {
		d = d % 360;
		if (d > 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
		double t2 = t * t, t3 = t2 * t;
		return new Vec3(
				cr(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
				cr(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
				cr(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
	}

	private static double cr(double a, double b, double c, double d, double t, double t2, double t3) {
		return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
	}
}
