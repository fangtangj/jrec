package com.rzx.jrec.core;

import com.rzx.jrec.core.backend.WindowsBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBuilderTest {

    private final WindowsBackend backend = new WindowsBackend();

    private List<String> build(RecordingConfig config) {
        return CommandBuilder.build("ffmpeg", config, backend);
    }

    @Test
    void fullScreenWithNoAudioEncodesDesktop() {
        List<String> cmd = build(RecordingConfig.builder()
                .mode(RecordingMode.FULL_SCREEN)
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(indexOf(cmd, "-f", "gdigrab") > 0, "should select the gdigrab input");
        assertTrue(indexOf(cmd, "-i", "desktop") > 0, "should capture the desktop");
        assertFalse(cmd.contains("-offset_x"), "full screen must not crop");
        assertTrue(cmd.contains("-an"), "no audio requested means -an");
        assertEquals("out.mp4", Path.of(cmd.get(cmd.size() - 1)).getFileName().toString());
    }

    @Test
    void regionIsCroppedAtCaptureTime() {
        List<String> cmd = build(RecordingConfig.builder()
                .region(new Rect(100, 50, 800, 600))
                .outputPath(Path.of("out.mp4"))
                .build());

        assertEquals("100", cmd.get(indexOf(cmd, "-offset_x", "100") + 1));
        assertEquals("50", cmd.get(indexOf(cmd, "-offset_y", "50") + 1));
        assertEquals("800x600", cmd.get(indexOf(cmd, "-video_size", "800x600") + 1));
    }

    @Test
    void oddRegionIsRoundedDownToEvenDimensions() {
        // H.264 refuses odd frame sizes; a one-pixel trim is invisible.
        List<String> cmd = build(RecordingConfig.builder()
                .region(new Rect(0, 0, 801, 601))
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(cmd.contains("800x600"), "801x601 should be normalised to 800x600");
    }

    @Test
    void singleAudioInputIsMappedDirectly() {
        List<String> cmd = build(RecordingConfig.builder()
                .microphone(new AudioDevice("Mic", AudioDeviceType.MICROPHONE))
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(indexOf(cmd, "-map", "1:a") > 0);
        assertFalse(cmd.contains("-filter_complex"), "one track needs no mixing");
        assertFalse(cmd.contains("-an"));
    }

    @Test
    void twoAudioInputsAreMixed() {
        List<String> cmd = build(RecordingConfig.builder()
                .microphone(new AudioDevice("Mic", AudioDeviceType.MICROPHONE))
                .systemAudio(new AudioDevice("Stereo Mix", AudioDeviceType.SYSTEM_LOOPBACK))
                .outputPath(Path.of("out.mp4"))
                .build());

        int filterIdx = cmd.indexOf("-filter_complex");
        assertTrue(filterIdx > 0, "two tracks must be mixed");
        assertTrue(cmd.get(filterIdx + 1).contains("amix=inputs=2"));
        assertTrue(cmd.contains("[aout]"));
    }

    @Test
    void deviceNameIsNotWrappedInQuotes() {
        // Regression guard: quoting the name makes FFmpeg fail with "I/O error",
        // because ProcessBuilder already passes it as a discrete argument.
        List<String> cmd = build(RecordingConfig.builder()
                .microphone(new AudioDevice("麦克风 (ToDesk Virtual Audio)", AudioDeviceType.MICROPHONE))
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(cmd.contains("audio=麦克风 (ToDesk Virtual Audio)"));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("audio=\"")), "device names must stay unquoted");
    }

    @Test
    void hardwareEncoderUsesItsOwnQualityFlag() {
        List<String> cmd = build(RecordingConfig.builder()
                .videoCodec("h264_nvenc")
                .outputPath(Path.of("out.mp4"))
                .build());

        assertTrue(cmd.contains("h264_nvenc"));
        assertTrue(cmd.contains("-cq"), "nvenc does not understand -crf");
        assertFalse(cmd.contains("-crf"));
    }

    @Test
    void regionModeWithoutRegionIsRejected() {
        RecordingConfig config = RecordingConfig.builder()
                .mode(RecordingMode.REGION)
                .outputPath(Path.of("out.mp4"))
                .build();

        assertThrows(RecorderException.class, config::validate);
    }

    @Test
    void sameDeviceForMicAndSystemIsRejected() {
        AudioDevice dev = new AudioDevice("Mic", AudioDeviceType.MICROPHONE);
        RecordingConfig config = RecordingConfig.builder()
                .microphone(dev)
                .systemAudio(dev)
                .outputPath(Path.of("out.mp4"))
                .build();

        assertThrows(RecorderException.class, config::validate);
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
