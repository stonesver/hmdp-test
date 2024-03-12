package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private  RedisWorker redisWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {

        //两张表操作，添加事务
        //1.查询
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断时间范围
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }
        //3.判断库存
        if(seckillVoucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        //用intern保证当用户id一样时返回的string对象一样
        Long  userId = UserHolder.getUser().getId();
        //悲观锁实现一人一单
        //锁放在函数外，防止当函数执行完时事务未提交而导致其他线程进入
        synchronized (userId.toString().intern()){
            //代理对象，确保事务生效
            //直接使用this.function()使用的是直接对象，@Transactional则是spring使用代理对象生效的
            //需要加入依赖aspectjweaver
            //并在启动类中添加注解@EnableAspectJAutoProxy(exposeProxy = true) 来暴露代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.CreateOrder(voucherId);
        }
        //在单体下没问题，但是在集群下仍然会有线程安全问题
        //因为集群下多个jvm有多个锁监视器，在一台上的锁不会影响另一台,需要使用分布式锁
    }
    @Transactional
    public Result CreateOrder(Long voucherId){
        //先判断订单是否存在
        Long  userId = UserHolder.getUser().getId();
        long count = query().eq("user_id",userId).eq("voucher_id",voucherId).count();
        if(count>0){
            return Result.fail("该用户已经下单");
        }
        //4.减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).gt("stock",0).update();
        if(!success){
            return Result.fail("库存不足！");
        }
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //5.1订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //5.2用户id

        voucherOrder.setUserId(userId);
        //5.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //6.返回id
        return Result.ok(orderId);
    }
}
