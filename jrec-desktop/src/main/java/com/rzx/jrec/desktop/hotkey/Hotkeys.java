package com.rzx.jrec.desktop.hotkey;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Picks the system-wide shortcut implementation for the running platform. */
public final class Hotkeys {

    private Hotkeys() {
    }

    public static GlobalHotkey create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsHotkey();
        }
        return new UnsupportedHotkey();
    }

    /**
     * Translates a JavaFX key press into a Win32-style combination.
     * <p>
     * Returning null rather than guessing matters: a field that quietly turned an ordinary
     * letter into a global shortcut would take that key away from every other application
     * on the machine.
     */
    public static HotkeySpec fromKeyEvent(KeyEvent event) {
        KeyCode code = event.getCode();
        String key = nameOf(code);
        int vk = vkOf(code);
        if (key == null || vk < 0) {
            return null;
        }

        Set<HotkeySpec.Mod> mods = EnumSet.noneOf(HotkeySpec.Mod.class);
        if (event.isControlDown()) {
            mods.add(HotkeySpec.Mod.CTRL);
        }
        if (event.isAltDown()) {
            mods.add(HotkeySpec.Mod.ALT);
        }
        if (event.isShiftDown()) {
            mods.add(HotkeySpec.Mod.SHIFT);
        }
        // On Windows the Windows key arrives as META in JavaFX.
        if (event.isMetaDown()) {
            mods.add(HotkeySpec.Mod.WIN);
        }

        HotkeySpec spec = HotkeySpec.of(mods, vk, key);
        return spec.isUsable() ? spec : null;
    }

    private static String nameOf(KeyCode code) {
        if (code.isLetterKey()) {
            return code.getName().toUpperCase(Locale.ROOT);
        }
        if (code.isDigitKey()) {
            String name = code.getName();
            return name.substring(name.length() - 1);
        }
        return switch (code) {
            case SPACE -> "Space";
            case ENTER -> "Enter";
            case TAB -> "Tab";
            case ESCAPE -> "Esc";
            case BACK_SPACE -> "Backspace";
            case INSERT -> "Insert";
            case DELETE -> "Delete";
            case HOME -> "Home";
            case END -> "End";
            case PAGE_UP -> "PageUp";
            case PAGE_DOWN -> "PageDown";
            case LEFT -> "Left";
            case UP -> "Up";
            case RIGHT -> "Right";
            case DOWN -> "Down";
            case PRINTSCREEN -> "PrintScreen";
            case PAUSE -> "Pause";
            default -> code.name().matches("F\\d{1,2}") ? code.name() : null;
        };
    }

    private static int vkOf(KeyCode code) {
        if (code.isLetterKey()) {
            return 0x41 + (code.getCode() - KeyCode.A.getCode());
        }
        if (code.isDigitKey()) {
            String name = code.getName();
            return 0x30 + Integer.parseInt(name.substring(name.length() - 1));
        }
        return switch (code) {
            case SPACE -> 0x20;
            case ENTER -> 0x0D;
            case TAB -> 0x09;
            case ESCAPE -> 0x1B;
            case BACK_SPACE -> 0x08;
            case INSERT -> 0x2D;
            case DELETE -> 0x2E;
            case HOME -> 0x24;
            case END -> 0x23;
            case PAGE_UP -> 0x21;
            case PAGE_DOWN -> 0x22;
            case LEFT -> 0x25;
            case UP -> 0x26;
            case RIGHT -> 0x27;
            case DOWN -> 0x28;
            case PRINTSCREEN -> 0x2C;
            case PAUSE -> 0x13;
            default -> {
                String name = code.name();
                if (name.matches("F\\d{1,2}")) {
                    yield 0x70 + Integer.parseInt(name.substring(1)) - 1;
                }
                yield -1;
            }
        };
    }
}
