package com.hmdp.service;

import com.hmdp.dto.OperationLogDTO;

/** 操作记录保存在 Redis，不需要数据库 Mapper。 */
public interface IOperationLogService {
    void saveLog(OperationLogDTO record);
}
