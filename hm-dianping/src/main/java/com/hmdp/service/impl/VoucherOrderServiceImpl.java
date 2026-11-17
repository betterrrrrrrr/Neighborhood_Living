package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("seckill.lua");
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("release_seckill.lua");

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Value("${hmdp.seckill.order-topic}")
    private String orderTopic;

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result == null) {
            return Result.fail("秒杀请求处理失败");
        }
        if (result != 0L) {
            return Result.fail(result == 1L ? "库存不足" : "不能重复下单");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(orderTopic, order);
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                releaseReservation(voucherId, userId);
                return Result.fail("订单消息发送失败，请重试");
            }
            return Result.ok(order.getId());
        } catch (Exception e) {
            releaseReservation(voucherId, userId);
            log.error("发送秒杀订单消息失败, orderId={}", order.getId(), e);
            return Result.fail("订单消息发送失败，请重试");
        }
    }

    private void releaseReservation(Long voucherId, Long userId) {
        Long released = stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (released == null) {
            log.error("释放秒杀库存预扣失败, voucherId={}, userId={}", voucherId, userId);
        }
    }

    @Override
    public void createVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if (!lock.tryLock()) {
            throw new IllegalStateException("用户订单正在处理中，稍后重试");
        }
        try {
            ((VoucherOrderServiceImpl) AopContext.currentProxy()).createVoucherOrderInTransaction(order);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Transactional
    public void createVoucherOrderInTransaction(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        if (query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0) {
            return;
        }

        boolean stockReserved = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!stockReserved) {
            throw new SeckillOrderRejectedException("数据库库存不足");
        }
        if (!save(order)) {
            throw new IllegalStateException("保存秒杀订单失败");
        }
    }

    public void releaseRejectedReservation(VoucherOrder order) {
        releaseReservation(order.getVoucherId(), order.getUserId());
    }

    public static class SeckillOrderRejectedException extends RuntimeException {
        public SeckillOrderRejectedException(String message) {
            super(message);
        }
    }
}
