package com.rbi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rbi.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.io.Serializable;
import java.util.Date;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /** 新增或更新（按主键 id 是否存在判断），与其它 mapper 的 save 语义保持一致 */
    default int save(Schedule entity) {
        Serializable id = entity.getId();
        if (id == null || selectById(id) == null) return insert(entity);
        return updateById(entity);
    }

    /**
     * 领取任务（多节点防重）：只有租约过期或从未加锁的节点能领到。
     * 返回 1 表示本节点获得执行权，0 表示其它节点正在执行。
     */
    @Update("UPDATE rbi_schedule SET locked_until = #{lease} "
            + "WHERE id = #{id} AND (locked_until IS NULL OR locked_until < #{now})")
    int claim(@Param("id") Long id, @Param("lease") Date lease, @Param("now") Date now);

    /** 释放租约（执行结束） */
    @Update("UPDATE rbi_schedule SET locked_until = NULL WHERE id = #{id}")
    int release(@Param("id") Long id);
}
