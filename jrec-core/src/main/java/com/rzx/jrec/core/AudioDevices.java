package com.rzx.jrec.core;

import com.rzx.jrec.core.backend.Backends;
import com.rzx.jrec.core.backend.CaptureBackend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates the audio devices FFmpeg can open on this machine, and labels each one
 * as a microphone or a system-loopback device.
 * <p>
 * The classification is name-based because none of the three backends exposes a
 * machine-readable "this is a loopback" flag. On Windows people meet this problem
 * through devices literally called "Stereo Mix" or "CABLE Output"; on Linux through
 * sources ending in {@code .monitor}; on macOS there is no loopback at all without a
 * kernel driver.
 */
public final class AudioDevices {

    /** Device name fragments that indicate a loopback (system playback) device. */
    private static final List<String> LOOPBACK_KEYWORDS = List.of(
            "立体声混音", "stereo mix", "what u hear", "wave out mix", "混音",
            "virtual-audio-capturer", "cable output", "voicemeeter",
            "virtual audio cable", "line 1 (virtual", "soundflower", "blackhole", "loopback"
    );

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("\"([^\"]+)\"\\s*\\((audio|video)\\)");

    private AudioDevices() {
    }

    /** All capturable audio devices, microphones first. */
    public static List<AudioDevice> scan() {
        Path ffmpeg = FfmpegLocator.require();
        List<AudioDevice> devices = switch (platform()) {
            case "windows" -> scanWindows(ffmpeg);
            case "macos" -> scanMac(ffmpeg);
            default -> scanLinux();
        };
        devices.sort((a, b) -> {
            if (a.isLoopback() != b.isLoopback()) {
                return a.isLoopback() ? 1 : -1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return devices;
    }

    /** The first device that looks like a loopback, if any. */
    public static AudioDevice findLoopback(List<AudioDevice> devices) {
        return devices.stream().filter(AudioDevice::isLoopback).findFirst().orElse(null);
    }

    /** The first device that looks like an ordinary microphone. */
    public static AudioDevice findMicrophone(List<AudioDevice> devices) {
        return devices.stream().filter(d -> !d.isLoopback()).findFirst().orElse(null);
    }

    private static String platform() {
        CaptureBackend backend = Backends.current();
        return backend.id();
    }

    private static List<AudioDevice> scanWindows(Path ffmpeg) {
        // The listing is written to stderr and the process always exits non-zero,
        // because "dummy" is not a real input device. Exit code is therefore ignored.
        String output = run(ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
        List<AudioDevice> devices = new ArrayList<>();
        Matcher m = WINDOWS_DEVICE.matcher(output);
        while (m.find()) {
            if (!"audio".equals(m.group(2))) {
                continue;
            }
            String name = m.group(1);
            devices.add(new AudioDevice(name, classify(name)));
        }
        return devices;
    }

    private static List<AudioDevice> scanLinux() {
        String output = runShell("pactl", "list", "sources", "short");
        List<AudioDevice> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] cols = line.trim().split("\\t");
            if (cols.length < 2) {
                continue;
            }
            String name = cols[1].trim();
            if (name.isEmpty()) {
                continue;
            }
            boolean monitor = name.endsWith(".monitor");
            devices.add(new AudioDevice(name, monitor ? AudioDeviceType.SYSTEM_LOOPBACK : AudioDeviceType.MICROPHONE));
        }
        if (devices.isEmpty()) {
            devices.add(new AudioDevice("default", AudioDeviceType.MICROPHONE));
        }
        return devices;
    }

    private static List<AudioDevice> scanMac(Path ffmpeg) {
        String output = run(ffmpeg, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", "");
        List<AudioDevice> devices = new ArrayList<>();
        boolean inAudioSection = false;
        Pattern entry = Pattern.compile("\\[(\\d+)]\\s+(.+)$");
        for (String line : output.split("\\R")) {
            if (line.contains("AVFoundation audio devices")) {
                inAudioSection = true;
                continue;
            }
            if (line.contains("AVFoundation video devices")) {
                inAudioSection = false;
                continue;
            }
            if (!inAudioSection) {
                continue;
            }
            Matcher m = entry.matcher(line.trim());
            if (m.find()) {
                // avfoundation addresses audio devices by index, but the index is useless
                // to a human, so keep the real name for display only.
                String index = m.group(1);
                String realName = m.group(2);
                devices.add(new AudioDevice(index, classify(realName), realName));
            }
        }
        return devices;
    }

    static AudioDeviceType classify(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : LOOPBACK_KEYWORDS) {
            if (lower.contains(keyword)) {
                return AudioDeviceType.SYSTEM_LOOPBACK;
            }
        }
        return AudioDeviceType.MICROPHONE;
    }

    private static String run(Path executable, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toString());
        cmd.addAll(List.of(args));
        return exec(cmd);
    }

    private static String runShell(String... args) {
        return exec(List.of(args));
    }

    private static String exec(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text;
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                text = buffer.toString(StandardCharsets.UTF_8);
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return text;
        } catch (IOException e) {
            throw new RecorderException("无法枚举音频设备：" + e.getMessage()
                    + "\n命令：" + String.join(" ", cmd), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecorderException("枚举音频设备被中断", e);
        }
    }
}
