package com.rbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.entity.ColumnPolicy;
import com.rbi.mapper.ColumnPolicyMapper;
import com.rbi.security.AuthContext;
import com.rbi.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 列级权限与数据脱敏。
 *
 * 与行级（rbi_data_scope）互补：
 *  - 行级决定"能看哪些行"；
 *  - 列级决定"能看哪些列 / 列值是否脱敏"。
 *
 * 策略按角色生效，ADMIN 不受限；同一列命中多条策略时取最严格的一条
 * （hidden > mask_* > 不处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColumnPolicyService {

    /** 策略严格度，数字越小越严格 */
    private static final Map<String, Integer> SEVERITY = new LinkedHashMap<>();

    static {
        SEVERITY.put("hidden", 0);
        SEVERITY.put("mask_all", 1);
        SEVERITY.put("mask_idcard", 2);
        SEVERITY.put("mask_phone", 3);
        SEVERITY.put("mask_email", 4);
        SEVERITY.put("mask_name", 5);
    }

    private final ColumnPolicyMapper columnPolicyMapper;
    private final PermissionService permissionService;

    /** 当前用户在该数据集上生效的列策略：列名(小写) -> policy */
    public Map<String, String> effectivePolicies(Long datasetId) {
        Map<String, String> result = new LinkedHashMap<>();
        CurrentUser me = AuthContext.get();
        if (me == null || me.isAdmin() || datasetId == null || datasetId <= 0) return result;

        List<Long> roleIds = permissionService.roleIdsOf(me);
        if (roleIds.isEmpty()) return result;

        LambdaQueryWrapper<ColumnPolicy> w = new LambdaQueryWrapper<>();
        w.eq(ColumnPolicy::getDatasetId, datasetId).in(ColumnPolicy::getRoleId, roleIds);
        for (ColumnPolicy p : columnPolicyMapper.selectList(w)) {
            if (p.getColumnName() == null || p.getPolicy() == null) continue;
            String col = p.getColumnName().toLowerCase(Locale.ROOT);
            String policy = p.getPolicy().toLowerCase(Locale.ROOT);
            Integer newSev = SEVERITY.get(policy);
            if (newSev == null) {
                log.warn("[列权限] 未知策略 {}，按 hidden 处理", policy);
                policy = "hidden";
                newSev = 0;
            }
            String existing = result.get(col);
            if (existing == null || SEVERITY.get(existing) > newSev) {
                result.put(col, policy);
            }
        }
        return result;
    }

    /** 被隐藏（不可见）的列名集合，供分析下推等场景做前置校验 */
    public Set<String> hiddenColumns(Long datasetId) {
        Set<String> hidden = new HashSet<>();
        for (Map.Entry<String, String> e : effectivePolicies(datasetId).entrySet()) {
            if ("hidden".equals(e.getValue())) hidden.add(e.getKey());
        }
        return hidden;
    }

    /** 对结果集应用列策略（隐藏列 + 值脱敏），返回新的行列表 */
    public List<Map<String, Object>> apply(Long datasetId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        Map<String, String> policies = effectivePolicies(datasetId);
        if (policies.isEmpty()) return rows;

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String policy = policies.get(e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT));
                if (policy == null) {
                    copy.put(e.getKey(), e.getValue());
                } else if ("hidden".equals(policy)) {
                    // 直接丢弃该列
                } else {
                    copy.put(e.getKey(), mask(e.getValue(), policy));
                }
            }
            out.add(copy);
        }
        return out;
    }

    String mask(Object value, String policy) {
        if (value == null) return null;
        String v = String.valueOf(value);
        switch (policy) {
            case "mask_all":
                return "******";
            case "mask_phone":
                return v.length() >= 7 ? v.substring(0, 3) + "****" + v.substring(v.length() - 4) : "****";
            case "mask_idcard":
                return v.length() >= 8 ? v.substring(0, 3) + "***********" + v.substring(v.length() - 4) : "****";
            case "mask_email": {
                int at = v.indexOf('@');
                if (at <= 0) return "****";
                String name = v.substring(0, at);
                String head = name.isEmpty() ? "" : name.substring(0, 1);
                return head + "***" + v.substring(at);
            }
            case "mask_name":
                return v.isEmpty() ? v : v.substring(0, 1) + "*";
            default:
                return "******";
        }
    }
}
