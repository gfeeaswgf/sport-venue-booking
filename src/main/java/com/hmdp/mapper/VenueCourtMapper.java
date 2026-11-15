package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VenueCourt;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VenueCourtMapper extends BaseMapper<VenueCourt> {
    @Select("SELECT * FROM tb_venue_court WHERE id = #{id} FOR UPDATE")
    VenueCourt lockById(@Param("id") Long id);
}
