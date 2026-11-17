package com.rbi.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rbi.common.BizException;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 通用 CRUD 服务
 */
@RequiredArgsConstructor
public class CrudService<T> {
    private final BaseMapper<T> mapper;

    public List<T> list() { return mapper.selectList(null); }

    public Page<T> page(int current, int size) {
        return mapper.selectPage(new Page<>(current, size), null);
    }

    public T get(Long id) { return mapper.selectById(id); }

    public Long save(T entity) {
        mapper.insert(entity);
        return (Long) getId(entity);
    }

    public boolean update(T entity) { return mapper.updateById(entity) > 0; }

    public boolean delete(Long id) { return mapper.deleteById(id) > 0; }

    private Object getId(T entity) {
        try {
            java.lang.reflect.Field f = findField(entity.getClass(), "id");
            if (f != null) { f.setAccessible(true); return f.get(entity); }
        } catch (Exception ignored) {}
        return 0L;
    }

    private java.lang.reflect.Field findField(Class<?> c, String name) {
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }
}
