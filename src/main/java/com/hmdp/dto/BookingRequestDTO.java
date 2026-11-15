package com.hmdp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 不接受预约人 ID、姓名、手机号等身份归属字段。 */
@Data
public class BookingRequestDTO {
    private Long slotId;
    private Boolean agreement;
    private List<BookingCompanionDTO> companions = new ArrayList<>();
}
