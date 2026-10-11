package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;

import com.cabletv.player.epg.EpgManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which programme the guide lands on when it opens. The exact-match-only version left the cursor on
 * the first programme of the day whenever the guide had a gap at the current time, which viewers
 * reported as "the list opens on the wrong programme" (live and catchup alike).
 */
public class ProgramFocusIndexTest {

    private static EpgManager.Program program(long start, long end) {
        return new EpgManager.Program("p", start, end);
    }

    private static List<EpgManager.Program> day() {
        // 10:00-11:00, 11:00-12:00, then a gap, then 15:00-16:00.
        return new ArrayList<>(Arrays.asList(
                program(10_000L, 11_000L),
                program(11_000L, 12_000L),
                program(15_000L, 16_000L)));
    }

    @Test
    public void landsOnTheProgrammeAiringNow() {
        assertEquals(1, ChannelListComponent.nearestProgramIndex(day(), 11_500L));
    }

    @Test
    public void landsOnTheMostRecentProgrammeWhenTheGuideHasAGap() {
        assertEquals("12:30 is inside the gap; the 11:00 programme is the last that started",
                1, ChannelListComponent.nearestProgramIndex(day(), 12_500L));
    }

    @Test
    public void landsOnTheMostRecentProgrammeWhenTheFeedIsStale() {
        // Feed ends at 16:00 but it is already 18:00: the last programme is still the nearest.
        assertEquals(2, ChannelListComponent.nearestProgramIndex(day(), 18_000L));
    }

    @Test
    public void landsOnTheFirstProgrammeWhenTheDayHasNotStarted() {
        assertEquals(0, ChannelListComponent.nearestProgramIndex(day(), 5_000L));
    }

    @Test
    public void ignoresLaterProgrammesWhenLookingBackwards() {
        // A catchup replay of the 10:00 programme must not be pulled forward by later entries.
        assertEquals(0, ChannelListComponent.nearestProgramIndex(day(), 10_500L));
    }

    @Test
    public void copesWithAnEmptyDay() {
        assertEquals(0, ChannelListComponent.nearestProgramIndex(new ArrayList<EpgManager.Program>(), 10_500L));
    }
}
