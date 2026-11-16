package com.hmdp.dto;

import com.hmdp.entity.BookingRequestTask;

import lombok.Data;

/** 对外只返回受理和结果元数据，不把请求表中的本人资料暴露出去。 */
@Data
public class BookingSubmissionDTO {
    private String requestId;
    private String state;
    private String orderId;
    private String message;

    public static BookingSubmissionDTO from(BookingRequestTask task) {
        BookingSubmissionDTO result = new BookingSubmissionDTO();
        result.setRequestId(task.getId());
        if ("SUCCEEDED".equals(task.getStatus())) {
            result.setState("SUCCEEDED");
            result.setOrderId(task.getId());
            result.setMessage("预约成功");
        } else if ("FAILED".equals(task.getStatus())) {
            result.setState("FAILED");
            result.setMessage(task.getFailureReason());
        } else {
            result.setState("PROCESSING");
            result.setMessage("请求已受理，正在确认预约");
        }
        return result;
    }
}
