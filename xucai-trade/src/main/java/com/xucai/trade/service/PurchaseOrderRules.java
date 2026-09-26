package com.xucai.trade.service;

import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.PurchaseOrderForm.Address;

public final class PurchaseOrderRules {
    private PurchaseOrderRules() { }
    public static Address normalize(Address address) {
        return new Address(address.recipient().trim(), address.phone().trim(), address.detail().trim());
    }
    public static void replayAddress(Address submitted, Address saved) {
        if (!normalize(submitted).equals(saved))
            throw new BusinessException(409,"该方案已成单，收货信息与原订单不同；原地址未修改，请查看原订单");
    }
    public static void confirmable(String planStatus, String requirementStatus, long planRevision, long currentRevision) {
        if (!"PENDING_CONFIRM".equals(planStatus) || !"OPEN".equals(requirementStatus) || planRevision != currentRevision)
            throw new BusinessException(409,"需求或方案状态已变化，请查看已有订单或重新编制方案");
    }
}
