package com.yilian.clothing.application.service;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.Operator;
import com.yilian.clothing.domain.model.Warehouse;
import com.yilian.clothing.domain.repository.SupportRepository;
import com.yilian.clothing.infrastructure.security.AuthContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 元数据与组织架构用例（尺码/颜色/季节/支付方式/仓库/员工）。 */
@Service
public class MetaAppService {

    private final CatalogAppService catalogAppService;
    private final SupportRepository supportRepository;

    public MetaAppService(CatalogAppService catalogAppService, SupportRepository supportRepository) {
        this.catalogAppService = catalogAppService;
        this.supportRepository = supportRepository;
    }

    public Map<String, Object> meta() {
        return catalogAppService.meta();
    }

    public List<Warehouse> warehouses() {
        Operator op = operator();
        return supportRepository.listWarehouses(op.shopId());
    }

    /** Q6/Q13：员工列表（店主可看，店员看不到团队信息） */
    public List<Map<String, Object>> users() {
        Operator op = operator();
        return supportRepository.listUsers(op.shopId()).stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.id(),
                        "phone", u.phone(),
                        "nickname", u.nickname(),
                        "role", u.role().name(),
                        "roleLabel", u.role().label(),
                        "status", u.status()))
                .toList();
    }

    private Operator operator() {
        Operator op = AuthContext.current();
        if (op == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return op;
    }
}
