package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(topic = "${hmdp.seckill.order-topic}", consumerGroup = "${rocketmq.consumer.group}")
public class VoucherOrderListener implements RocketMQListener<VoucherOrder> {

    private final VoucherOrderServiceImpl voucherOrderService;

    public VoucherOrderListener(VoucherOrderServiceImpl voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    public void onMessage(VoucherOrder order) {
        try {
            voucherOrderService.createVoucherOrder(order);
        } catch (VoucherOrderServiceImpl.SeckillOrderRejectedException e) {
            voucherOrderService.releaseRejectedReservation(order);
            log.warn("秒杀订单被数据库拒绝, orderId={}, reason={}", order.getId(), e.getMessage());
        }
    }
}
