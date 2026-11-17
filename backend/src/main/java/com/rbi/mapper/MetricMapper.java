package com.rbi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rbi.entity.Metric;
import org.apache.ibatis.annotations.Mapper;

import java.io.Serializable;

@Mapper
public interface MetricMapper extends BaseMapper<Metric> {

    /** 新增或更新（按主键 id 是否存在判断） */
    default int save(Metric entity) {
        Serializable id = null;
        try {
            java.lang.reflect.Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            Object raw = f.get(entity);
            if (raw instanceof Serializable) id = (Serializable) raw;
        } catch (Exception ignored) {}
        if (id == null) return insert(entity);
        if (selectById(id) == null) return insert(entity);
        return updateById(entity);
    }
}
