package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import cn.hutool.core.bean.BeanUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private  RedisWorker redisWorker;

    //加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    //这里使用的是jvm内存，在高并发等情况内存有限制
    //服务宕机时，内存中数据安全也有隐患
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //当前类初始化完毕后执行init，开启线程run
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run(){
            while(true){
                try {
                    //获取消息队列中的订单信息 redis命令：XAREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String ,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    //获取失败则继续继续循环
                    if(list==null||list.isEmpty()){
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String,Object,Object> record = list.get(0);
                    Map<Object,Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);


                    //成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //下单完成ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }  catch (Exception e){
                    log.error("处理订单错误",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    //获取pending list中的订单信息 redis命令：XAREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String ,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    //获取失败则结束循环
                    if(list==null||list.isEmpty()){
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String,Object,Object> record = list.get(0);
                    Map<Object,Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);

                    //成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //下单完成ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }  catch (Exception e){
                    log.error("处理pending list订单错误",e);
                    //避免过于频繁

                }
            }
        }
    }
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run(){
//            while(true){
//                //1.获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                }  catch (Exception e){
//                    log.error("处理订单错误",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户，不能从userholder里拿
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁(这里可以不用加锁)
        boolean isLock= lock.tryLock();
        //判断获取是否成功
        if(!isLock){
            //失败结束
            log.error("不允许重复下单");
            return;
        }
        //成功执行业务
        try {
            //子线程无法拿到代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.CreateOrder(voucherOrder);
        } finally {
            //释放锁之前要判断是否是自己的锁（线程id）
            //注意不同的jvm线程id可能冲突，要使用uuid进行区分
            lock.unlock();
        }
    }

    private  IVoucherOrderService proxy;
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //1.执行lua脚本进行查询
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString());
//        //2.判断是否为0
//        int r = result.intValue();
//        if(r!=0){
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //为0
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单id
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextId("order");
        //1.执行lua脚本进行查询
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString(), String.valueOf(orderId));
        //2.判断是否为0
        int r = result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //为0
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
    @Transactional
    public void CreateOrder(VoucherOrder voucherOrder){
        //先判断订单是否存在
        //异步的无法从Userholder里拿
        Long  userId = voucherOrder.getId();
        long count = query().eq("user_id",userId).eq("voucher_id",voucherOrder.getVoucherId()).count();
        if(count>0){
            log.error("该用户已经下单");
            return;
        }
        //4.减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0).update();
        if(!success){
            log.error("库存不足！");
            return;
        }
        //5.创建订单
        save(voucherOrder);
    }
}
