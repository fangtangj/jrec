package com.rzx.jrec.desktop.hotkey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One key combination, independent of the platform API that registers it.
 * <p>
 * Kept as data rather than as a bunch of ints at the call site so the combination can be
 * shown to the user, persisted between sessions, and compared for equality when a
 * rebind fails and the old one has to be restored.
 */
public final class HotkeySpec {

    /** Win32 modifier flags. {@code MOD_WIN} is 0x0008, {@code MOD_NOREPEAT} 0x4000. */
    public enum Mod {
        CTRL("Ctrl", 0x0002),
        ALT("Alt", 0x0001),
        SHIFT("Shift", 0x0004),
        WIN("Win", 0x0008);

        private final String display;
        private final int flag;

        Mod(String display, int flag) {
            this.display = display;
            this.flag = flag;
        }

        public int flag() {
            return flag;
        }

        public String display() {
            return display;
        }
    }

    private static final Map<String, Integer> NAME_TO_VK = Map.ofEntries(
            Map.entry("Space", 0x20), Map.entry("Enter", 0x0D), Map.entry("Tab", 0x09),
            Map.entry("Esc", 0x1B), Map.entry("Backspace", 0x08), Map.entry("Insert", 0x2D),
            Map.entry("Delete", 0x2E), Map.entry("Home", 0x24), Map.entry("End", 0x23),
            Map.entry("PageUp", 0x21), Map.entry("PageDown", 0x22),
            Map.entry("Left", 0x25), Map.entry("Up", 0x26), Map.entry("Right", 0x27),
            Map.entry("Down", 0x28), Map.entry("PrintScreen", 0x2C), Map.entry("Pause", 0x13)
    );

    private final Set<Mod> mods;
    private final int vk;
    private final String key;

    private HotkeySpec(Set<Mod> mods, int vk, String key) {
        this.mods = mods;
        this.vk = vk;
        this.key = key;
    }

    public static HotkeySpec of(Set<Mod> mods, int vk, String key) {
        EnumSet<Mod> copy = EnumSet.noneOf(Mod.class);
        copy.addAll(mods);
        return new HotkeySpec(copy, vk, key);
    }

    /** A combination is usable when it has a key, and either a modifier or is a function key. */
    public boolean isUsable() {
        if (key == null || key.isEmpty() || vk <= 0) {
            return false;
        }
        if (!mods.isEmpty()) {
            return true;
        }
        // Bare function keys are a normal choice for a recorder and are accepted; bare
        // letters are not, because they would swallow ordinary typing system-wide.
        return key.matches("F\\d{1,2}");
    }

    public Set<Mod> mods() {
        return EnumSet.copyOf(mods.isEmpty() ? EnumSet.noneOf(Mod.class) : mods);
    }

    public int vk() {
        return vk;
    }

    public String key() {
        return key;
    }

    public int modifierMask() {
        int mask = 0;
        for (Mod mod : mods) {
            mask |= mod.flag();
        }
        return mask;
    }

    /** Display form, modifiers first in a stable order: {@code Ctrl+Alt+S}. */
    public String label() {
        if (!isUsable()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (Mod mod : mods) {
            sb.append(mod.display()).append('+');
        }
        return sb.append(key).toString();
    }

    @Override
    public String toString() {
        return label();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HotkeySpec spec)) {
            return false;
        }
        return vk == spec.vk && mods.equals(spec.mods);
    }

    @Override
    public int hashCode() {
        return vk * 31 + mods.hashCode();
    }

    /**
     * Rebuilds a combination from its own label, so the preference file holds something a
     * human can read and hand-edit. Returns null on anything unrecognised; the caller then
     * falls back to the built-in default instead of failing to start.
     */
    public static HotkeySpec parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> tokens = new ArrayList<>(List.of(text.split("\\+")));
        if (tokens.isEmpty()) {
            return null;
        }
        EnumSet<Mod> mods = EnumSet.noneOf(Mod.class);
        for (int i = 0; i < tokens.size() - 1; i++) {
            switch (tokens.get(i).trim().toLowerCase(Locale.ROOT)) {
                case "ctrl", "control" -> mods.add(Mod.CTRL);
                case "alt" -> mods.add(Mod.ALT);
                case "shift" -> mods.add(Mod.SHIFT);
                case "win", "meta", "super" -> mods.add(Mod.WIN);
                default -> {
                    return null;
                }
            }
        }
        String key = tokens.get(tokens.size() - 1).trim();
        if (key.isEmpty()) {
            return null;
        }
        HotkeySpec spec = new HotkeySpec(mods, vkFor(key), canonical(key));
        return spec.isUsable() ? spec : null;
    }

    private static String canonical(String key) {
        if (key.length() == 1) {
            return key.toUpperCase(Locale.ROOT);
        }
        for (String known : NAME_TO_VK.keySet()) {
            if (known.equalsIgnoreCase(key)) {
                return known;
            }
        }
        return key;
    }

    private static int vkFor(String key) {
        if (key.length() == 1) {
            char c = Character.toUpperCase(key.charAt(0));
            if (c >= 'A' && c <= 'Z') {
                return 0x41 + (c - 'A');
            }
            if (c >= '0' && c <= '9') {
                return 0x30 + (c - '0');
            }
            return -1;
        }
        for (Map.Entry<String, Integer> entry : NAME_TO_VK.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        if (key.matches("[Ff]\\d{1,2}")) {
            int n = Integer.parseInt(key.substring(1));
            if (n >= 1 && n <= 24) {
                return 0x70 + n - 1;
            }
        }
        return -1;
    }
}
