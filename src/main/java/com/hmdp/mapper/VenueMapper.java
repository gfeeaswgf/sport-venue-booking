package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Venue;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VenueMapper extends BaseMapper<Venue> {
    @Select("SELECT * FROM tb_venue WHERE id = #{id} FOR UPDATE")
    Venue lockById(@Param("id") Long id);
}
