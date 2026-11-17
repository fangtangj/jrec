package com.rzx.jrec.desktop.hotkey;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the rule that decides which combination gets registered.
 * <p>
 * The Win32 call itself cannot be exercised here, but the decision can — and the
 * decision is where the bug was: a saved combination that another program had taken
 * used to leave the application with no shortcut at all.
 */
class HotkeyCandidatesTest {

    /**
     * A binder that refuses the listed combinations and accepts everything else.
     *
     * @param taken combinations the system refuses
     */
    private static HotkeyCandidates.Binder binderRefusing(HotkeySpec... taken) {
        List<HotkeySpec> refused = new ArrayList<>(List.of(taken));
        return spec -> {
            for (HotkeySpec bad : refused) {
                if (bad.equals(spec)) {
                    return spec.label() + " 已被其它程序占用";
                }
            }
            return null;
        };
    }

    @Test
    void exactBindingTakesTheChosenCombinationWhenItIsFree() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");

        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectExact(chosen, binderRefusing());

        assertTrue(selection.isChosen());
        assertEquals(chosen, selection.chosen());
        assertNull(selection.preferredFailure());
    }

    @Test
    void exactBindingRefusesRatherThanSilentlyBindingSomethingElse() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");

        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectExact(chosen, binderRefusing(chosen));

        assertNull(selection.chosen(), "被占用的组合不能被悄悄替换成别的");
        assertEquals("Ctrl+Alt+R 已被其它程序占用", selection.preferredFailure());
    }

    @Test
    void fallbackTakesTheFirstFreeDefaultWhenTheChosenOneIsTaken() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");

        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectWithFallback(chosen, binderRefusing(chosen));

        assertTrue(selection.isChosen());
        assertEquals("Ctrl+Alt+S", selection.chosen().label());
        assertEquals("Ctrl+Alt+R 已被其它程序占用", selection.preferredFailure(),
                "回退之后仍要说明原来的组合为什么没成");
    }

    @Test
    void fallbackSkipsEveryDefaultThatIsAlsoTaken() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");

        HotkeyCandidates.Selection selection = HotkeyCandidates.selectWithFallback(chosen,
                binderRefusing(chosen,
                        HotkeyCandidates.DEFAULTS.get(0),
                        HotkeyCandidates.DEFAULTS.get(1)));

        assertTrue(selection.isChosen());
        assertEquals("Ctrl+Alt+F9", selection.chosen().label());
    }

    @Test
    void fallbackDoesNotRetryTheCombinationThatWasAlreadyRefused() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");
        List<HotkeySpec> attempts = new ArrayList<>();

        HotkeyCandidates.selectWithFallback(chosen, spec -> {
            attempts.add(spec);
            return spec.equals(chosen) ? spec.label() + " 已被其它程序占用" : null;
        });

        long triesOfChosen = attempts.stream().filter(spec -> spec.equals(chosen)).count();
        assertEquals(1, triesOfChosen, "已被拒绝的组合不该再试一次");
    }

    @Test
    void fallbackReportsFailureWhenNothingCanBeBound() {
        HotkeySpec chosen = HotkeySpec.of(EnumSet.of(HotkeySpec.Mod.CTRL, HotkeySpec.Mod.ALT), 0x52, "R");
        HotkeySpec[] allTaken = HotkeyCandidates.DEFAULTS.toArray(new HotkeySpec[0]);

        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectWithFallback(chosen, binderRefusing(allTaken));

        assertNull(selection.chosen());
        assertEquals("Ctrl+Alt+R 已被其它程序占用", selection.preferredFailure());
    }

    @Test
    void noPreferenceMeansTheFirstFreeDefault() {
        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectWithFallback(null, binderRefusing());

        assertTrue(selection.isChosen());
        assertEquals("Ctrl+Alt+R", selection.chosen().label());
    }

    @Test
    void exactBindingOfNothingBindsNothing() {
        HotkeyCandidates.Selection selection =
                HotkeyCandidates.selectExact(null, binderRefusing());

        assertNull(selection.chosen());
        assertNull(selection.preferredFailure());
    }
}
