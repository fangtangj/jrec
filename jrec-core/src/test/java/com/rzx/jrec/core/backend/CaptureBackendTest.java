package com.rzx.jrec.core.backend;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.AudioDeviceType;
import com.rzx.jrec.core.CommandBuilder;
import com.rzx.jrec.core.RecordingConfig;
import com.rzx.jrec.core.RecordingMode;
import com.rzx.jrec.core.Rect;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the platform backends that this development machine cannot run.
 * <p>
 * macOS and Linux have no machine here, so the only thing that can catch a mistake in
 * them is reading the generated command line. The arguments are what FFmpeg sees; a
 * wrong one shows up as a recording that never starts on a platform nobody tested.
 */
class CaptureBackendTest {

    private static List<String> build(CaptureBackend backend, RecordingConfig config) {
        return CommandBuilder.build("ffmpeg", config, backend);
    }

    // ------------------------------------------------------------------ macOS

    @Test
    void macosUsesAvfoundationWithAScreenIndex() {
        List<String> cmd = build(new MacBackend(), RecordingConfig.builder()
                .mode(RecordingMode.FULL_SCREEN)
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(cmd.contains("avfoundation"), "macOS captures through avfoundation");
        assertTrue(cmd.contains("1:none"), "the default screen index is 1");
        assertFalse(cmd.contains("-offset_x"), "avfoundation cannot crop at capture time");
    }

    @Test
    void macosCropsTheRegionWithAFilterBecauseAvfoundationCannotCrop() {
        List<String> cmd = build(new MacBackend(), RecordingConfig.builder()
                .region(new Rect(100, 50, 800, 600))
                .outputPath(Path.of("out.mp4"))
                .build());

        int filterIdx = cmd.indexOf("-vf");
        assertTrue(filterIdx > 0, "the region has to be applied as a crop filter");
        assertEquals("crop=800:600:100:50", cmd.get(filterIdx + 1));
        assertFalse(cmd.contains("-offset_x"), "no capture-time cropping on macOS");
    }

    @Test
    void macosDoesNotClaimSystemAudioWithoutAVirtualDriver() {
        assertFalse(new MacBackend().supportsSystemAudio(),
                "macOS needs BlackHole or similar before system audio exists");
    }

    @Test
    void macosAudioInputUsesTheNoneVideoSyntax() {
        List<String> args = new MacBackend().audioInputArgs(
                new AudioDevice("Mic", AudioDeviceType.MICROPHONE));

        assertTrue(args.contains("none:Mic"), "an audio-only input needs the empty video part");
    }

    // ------------------------------------------------------------------ Linux

    @Test
    void linuxUsesX11grabAndReadsTheDisplayFromTheEnvironment() {
        List<String> cmd = build(new LinuxBackend(), RecordingConfig.builder()
                .mode(RecordingMode.FULL_SCREEN)
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(cmd.contains("x11grab"), "Linux captures through x11grab");
        String display = System.getenv().getOrDefault("DISPLAY", ":0.0");
        assertTrue(cmd.contains(display), "the input is the X display");
    }

    @Test
    void linuxCropsTheRegionAtCaptureTimeWithAnOffsetIntoTheDisplay() {
        List<String> cmd = build(new LinuxBackend(), RecordingConfig.builder()
                .region(new Rect(100, 50, 800, 600))
                .outputPath(Path.of("out.mp4"))
                .build());

        assertEquals("800x600", cmd.get(indexOf(cmd, "-video_size", "800x600") + 1));
        String display = System.getenv().getOrDefault("DISPLAY", ":0.0");
        assertTrue(cmd.contains(display + "+100,50"), "the input points at the region's corner");
        assertFalse(cmd.contains("-vf"), "x11grab crops at capture time, no filter needed");
    }

    @Test
    void linuxOffersSystemAudioBecausePulseExposesMonitorSources() {
        assertTrue(new LinuxBackend().supportsSystemAudio(),
                "PulseAudio monitor sources make system audio available on Linux");
    }

    @Test
    void linuxAudioInputUsesThePulseFormat() {
        List<String> args = new LinuxBackend().audioInputArgs(
                new AudioDevice("alsa_output.monitor", AudioDeviceType.SYSTEM_LOOPBACK));

        assertTrue(indexOf(args, "-f", "pulse") >= 0, "the audio input must select the pulse format");
        assertTrue(args.contains("alsa_output.monitor"));
    }

    // ------------------------------------------------------------------ shared

    @Test
    void everyBackendProducesACommandThatNamesAnOutputFile() {
        List<CaptureBackend> backends = List.of(new WindowsBackend(), new MacBackend(), new LinuxBackend());
        for (CaptureBackend backend : backends) {
            List<String> cmd = build(backend, RecordingConfig.builder()
                    .outputPath(Path.of("out.mp4"))
                    .build());
            assertEquals("out.mp4", Path.of(cmd.get(cmd.size() - 1)).getFileName().toString(),
                    backend.id() + " must end with the output file");
            assertTrue(cmd.contains("-an"), backend.id() + " with no audio must disable audio");
        }
    }

    @Test
    void everyBackendNamesItselfDistinctly() {
        List<String> ids = List.of(new WindowsBackend(), new MacBackend(), new LinuxBackend())
                .stream().map(CaptureBackend::id).toList();

        assertEquals(ids.size(), ids.stream().distinct().count(), "backend ids must be distinct");
        assertTrue(ids.contains("windows") && ids.contains("macos") && ids.contains("linux"));
    }

    private static int indexOf(List<String> cmd, String a, String b) {
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (cmd.get(i).equals(a) && cmd.get(i + 1).equals(b)) {
                return i;
            }
        }
        return -1;
    }
}
