package com.hmdp.service;

import com.hmdp.dto.BookingRequestDTO;
import com.hmdp.dto.BookingSubmissionDTO;

import java.util.List;

public interface IAsyncBookingService {
    BookingSubmissionDTO accept(BookingRequestDTO input, String key, String fingerprint);

    BookingSubmissionDTO result(String requestId);

    List<BookingSubmissionDTO> recent();
}
