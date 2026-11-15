package com.hmdp;

import com.hmdp.dto.BookingCompanionDTO;
import com.hmdp.utils.BookingException;
import com.hmdp.utils.BookingRules;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class BookingRulesTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 12, 0);

    @Test
    void rejectsPastAndStartedSlots() {
        assertThrows(
                BookingException.class,
                () ->
                        BookingRules.validateSlot(
                                now.toLocalDate(), LocalTime.NOON, LocalTime.of(13, 0), now));
        assertThrows(
                BookingException.class,
                () ->
                        BookingRules.validateSlot(
                                now.toLocalDate().minusDays(1),
                                LocalTime.of(14, 0),
                                LocalTime.of(15, 0),
                                now));
    }

    @Test
    void acceptsFutureSlotAndRejectsOutsideSevenDays() {
        assertDoesNotThrow(
                () ->
                        BookingRules.validateSlot(
                                now.toLocalDate().plusDays(6),
                                LocalTime.of(14, 0),
                                LocalTime.of(15, 0),
                                now));
        assertThrows(
                BookingException.class,
                () ->
                        BookingRules.validateSlot(
                                now.toLocalDate().plusDays(7),
                                LocalTime.of(14, 0),
                                LocalTime.of(15, 0),
                                now));
        assertThrows(
                BookingException.class,
                () ->
                        BookingRules.validateSlot(
                                now.toLocalDate(), LocalTime.of(15, 0), LocalTime.of(14, 0), now));
    }

    @Test
    void enforcesCancellationBoundaryAndState() {
        LocalDateTime start = now.plusHours(1);
        assertTrue(BookingRules.canCancel("RESERVED", start, 60, now));
        assertFalse(BookingRules.canCancel("RESERVED", start, 60, now.plusSeconds(1)));
        assertFalse(BookingRules.canCancel("COMPLETED", start, 0, now));
        assertFalse(BookingRules.canCancel("RESERVED", now, 0, now));
    }

    @Test
    void verifiesOnlyInsideWindow() {
        assertTrue(BookingRules.canVerify("RESERVED", now.plusMinutes(30), now.plusHours(1), now));
        assertFalse(BookingRules.canVerify("RESERVED", now.plusMinutes(31), now.plusHours(1), now));
        assertFalse(BookingRules.canVerify("CANCELLED", now, now.plusHours(1), now));
        assertFalse(BookingRules.canVerify("RESERVED", now.minusHours(1), now, now));
    }

    @Test
    void rejectsDuplicateIncompleteAndExcessCompanions() {
        BookingCompanionDTO a = new BookingCompanionDTO();
        a.setName("同学甲");
        a.setStudentNo("2026001");
        assertThrows(
                BookingException.class,
                () -> BookingRules.validateCompanions(Arrays.asList(a), "2026001", 6, "COURT"));
        assertThrows(
                BookingException.class,
                () -> BookingRules.validateCompanions(Arrays.asList(a, a), "2026002", 6, "COURT"));
        assertThrows(
                BookingException.class,
                () -> BookingRules.validateCompanions(Arrays.asList(a), "2026002", 1, "SESSION"));
        a.setStudentNo("");
        assertThrows(
                BookingException.class,
                () -> BookingRules.validateCompanions(Arrays.asList(a), "2026002", 6, "COURT"));
    }

    @Test
    void emptyCompanionsPermitSoloBooking() {
        assertDoesNotThrow(
                () ->
                        BookingRules.validateCompanions(
                                Collections.emptyList(), "2026001", 6, "COURT"));
    }
}
