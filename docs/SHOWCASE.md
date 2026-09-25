# Showcase & visual QA tools (fork addition)

Tools added on top of mcpfabric to check how items, resource packs and plugins really look in the vanilla client,
and to record showcase videos. Client-only.

| MCP tool | RPC | What it does |
|---|---|---|
| `connect_server` / `disconnect_server` | `client.connect` / `client.disconnect` | Join `host:port` with the server resource pack accepted up front (no prompt) |
| `set_perspective` | `view.setPerspective` | F5: `first_person`, `third_person_back`, `third_person_front` |
| `set_hud` | `view.setHud` | F1 (also hides the hand) |
| `set_chat` | `view.setChat` | Hide the chat overlay locally; messages still arrive |
| `set_fov` | `view.setFov` | Field of view 30..110 |
| `clear_toasts` | `view.clearToasts` | Dismiss top-right notifications before a capture |
| `view_state` | `view.state` | Perspective, HUD, chat, FOV, window size, in world, `loading`, `ready` (wait for it) |
| `screenshot_to_file` | `vision.screenshotToFile` | Full-resolution PNG written on the game machine |
| `start_recording` / `stop_recording` / `recording_status` | `record.*` | Real-time MP4 (H.264, x264 or NVENC) through ffmpeg |
| `camera_fixed` / `camera_path` / `camera_orbit` / `camera_release` / `camera_state` | `camera.*` | Cinematic camera: fixed shot, keyframed move (Catmull-Rom, eased, wall-clock timed), orbit around a point or the player; the player stays rendered |

Recording reads every rendered frame back asynchronously (the vanilla screenshot path, so the video is exactly
what the player sees; a mixin at the end of `Minecraft#runTick` triggers it) and feeds ffmpeg at a fixed frame
rate paced by the wall clock: the video lasts as long as the recording and anything driven by the real clock
(e.g. item models animated with `minecraft:local_time`) plays at its true speed. Set `ffmpegPath` in
`config/mcpfabric.config.json`. Keep the window size constant while recording.
