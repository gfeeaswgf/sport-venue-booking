package com.hmdp;

import static org.junit.jupiter.api.Assertions.*;

import com.hmdp.utils.BookingResultEvent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class BookingResultEventTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    void retainsStableBusinessIdentityAcrossRepeatedEncoding() {
        assertEquals(ID, BookingResultEvent.decode(BookingResultEvent.encode(ID)));
        String body = new String(BookingResultEvent.encode(ID), StandardCharsets.UTF_8);
        assertTrue(body.contains(ID + ":result:v1"));
        assertFalse(body.contains("userId"));
    }

    @Test
    void schemaVersionMustBeIntegerRatherThanTruncatedFraction() {
        String body =
                "{\"schemaVersion\":1.5,\"requestId\":\""
                        + ID
                        + "\",\"eventId\":\""
                        + ID
                        + ":result:v1\"}";
        assertThrows(
                IllegalArgumentException.class,
                () -> BookingResultEvent.decode(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWrongSchemaIdentityAndOversizedPayload() {
        assertThrows(IllegalArgumentException.class, () -> BookingResultEvent.encode("bad"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BookingResultEvent.decode(
                                ("{\"schemaVersion\":2,\"requestId\":\""
                                                + ID
                                                + "\",\"eventId\":\"x\"}")
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IllegalArgumentException.class, () -> BookingResultEvent.decode(new byte[1024]));
    }
}
