package com.rzx.jrec.desktop.hotkey;

import java.util.List;

/**
 * Which combination to register with the system.
 * <p>
 * The decision lives here, away from the Win32 call, because the Win32 call cannot be
 * exercised in a test and the decision is where the bug was: a saved combination that
 * another program took must not leave the application with no shortcut at all.
 * <p>
 * Two rules, and they are deliberately different:
 * <ul>
 *   <li><b>Exact.</b> The user pressed this combination and is watching. If the system
 *       refuses it, the attempt fails and the old binding stays — quietly binding
 *       something else would leave the user pressing a key that does nothing.</li>
 *   <li><b>With fallback.</b> The application is starting up and nobody is watching. The
 *       saved combination may have been taken by another program since the last run, and
 *       a recorder without a shortcut is a recorder nobody uses. Falling back to a free
 *       default keeps it usable.</li>
 * </ul>
 */
public final class HotkeyCandidates {

    /**
     * Built-in combinations, in the order they are tried. The first one is the documented
     * default; the rest exist because that one is occupied often enough to be a fact of
     * life rather than a hypothetical.
     */
    public static final List<HotkeySpec> DEFAULTS = List.of(
            HotkeySpec.of(java.util.EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R"),
            HotkeySpec.of(java.util.EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x53, "S"),
            HotkeySpec.of(java.util.EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x78, "F9"),
            HotkeySpec.of(java.util.EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.SHIFT), 0x78, "F9")
    );

    /**
     * Registers one combination.
     *
     * @return null when the system accepted it, otherwise the reason it refused
     */
    @FunctionalInterface
    public interface Binder {
        String bind(HotkeySpec spec);
    }

    /**
     * The outcome of a selection: what was registered, and — when a preferred combination
     * was tried — why the system refused it.
     *
     * @param chosen           the combination that got registered, or null when none could be
     * @param preferredFailure why the user's own combination was refused, or null when it
     *                         was accepted or when there was no user combination to try.
     *                         Kept even when a fallback was registered successfully, so
     *                         callers can tell the user their saved combination was
     *                         replaced; do not treat a non-null value here as total failure.
     */
    public record Selection(HotkeySpec chosen, String preferredFailure) {

        public boolean isChosen() {
            return chosen != null;
        }
    }

    private HotkeyCandidates() {
    }

    /**
     * Registers only the given combination.
     *
     * @param preferred the combination to register, or null to register nothing
     */
    public static Selection selectExact(HotkeySpec preferred, Binder binder) {
        if (preferred == null) {
            return new Selection(null, null);
        }
        String refusal = binder.bind(preferred);
        return refusal == null
                ? new Selection(preferred, null)
                : new Selection(null, refusal);
    }

    /**
     * Registers the given combination when the system accepts it, and otherwise the first
     * free default.
     *
     * <p>The preferred combination is never retried as a fallback: the system has already
     * refused it.
     */
    public static Selection selectWithFallback(HotkeySpec preferred, Binder binder) {
        String preferredFailure = null;
        if (preferred != null) {
            preferredFailure = binder.bind(preferred);
            if (preferredFailure == null) {
                return new Selection(preferred, null);
            }
        }
        for (HotkeySpec candidate : DEFAULTS) {
            if (candidate.equals(preferred)) {
                continue;
            }
            if (binder.bind(candidate) == null) {
                return new Selection(candidate, preferredFailure);
            }
        }
        return new Selection(null, preferredFailure);
    }
}
