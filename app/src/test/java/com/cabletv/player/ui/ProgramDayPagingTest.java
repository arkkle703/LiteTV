package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The guide's day stepping has to report when it does not move. Most EPG feeds carry only today
 * and tomorrow, so LEFT at the earliest day is the common case, and silently doing nothing left the
 * viewer stuck in the programme column with BACK as the only way out (reported from a television).
 */
public class ProgramDayPagingTest {

    /** The day a step lands on, or the current one when the step does not move. */
    private static int dayAfter(java.util.List<Integer> days, int currentOffset, int delta) {
        int index = ChannelListComponent.nextDayIndex(days, currentOffset, delta);
        return index < 0 ? currentOffset : days.get(index);
    }

    private static final java.util.List<Integer> THREE_DAYS = Arrays.asList(-1, 0, 1);

    @Test
    public void stepsWithinTheAvailableDays() {
        assertEquals(1, dayAfter(THREE_DAYS, 0, 1));
        assertEquals(0, dayAfter(THREE_DAYS, -1, 1));
        assertEquals(0, dayAfter(THREE_DAYS, 1, -1));
        assertEquals(-1, dayAfter(THREE_DAYS, 0, -1));
    }

    @Test
    public void reportsNoMoveAtEitherEnd() {
        java.util.List<Integer> days = Arrays.asList(0, 1);
        assertEquals("today is the earliest day in most feeds; LEFT must be able to leave",
                -1, ChannelListComponent.nextDayIndex(days, 0, -1));
        assertEquals("there is nothing past tomorrow either",
                -1, ChannelListComponent.nextDayIndex(days, 1, 1));
    }

    @Test
    public void reportsNoMoveWithASingleDay() {
        java.util.List<Integer> days = Collections.singletonList(0);
        assertEquals(-1, ChannelListComponent.nextDayIndex(days, 0, -1));
        assertEquals(-1, ChannelListComponent.nextDayIndex(days, 0, 1));
    }

    @Test
    public void reportsNoMoveWithoutAnyDays() {
        assertEquals(-1, ChannelListComponent.nextDayIndex(Collections.<Integer>emptyList(), 0, -1));
        assertEquals(-1, ChannelListComponent.nextDayIndex(null, 0, 1));
    }

    @Test
    public void reportsNoMoveWhenTheCurrentDayIsNotInTheList() {
        // The selected day can fall outside the window (the data moved on); stepping must not
        // silently jump somewhere else.
        assertEquals(-1, ChannelListComponent.nextDayIndex(Arrays.asList(1, 2), 0, -1));
        assertEquals(-1, ChannelListComponent.nextDayIndex(Arrays.asList(1, 2), 0, 1));
    }
}
