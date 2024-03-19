package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheCilent;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.redis.connection.RedisGeoCommands;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private CacheCilent cacheCilent;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisWorker redisWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = ()->{
            for(int i=0;i<100;i++){
                long id =redisWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };

        long begin=System.currentTimeMillis();
        for(int i=0;i<300;i++){
           es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }

    @Resource
    private RedissonClient redissonClient;
    @Test
    void testRedisson() throws InterruptedException {
        //获取锁
        RLock lock = redissonClient.getLock("anylock");
        boolean isLock = lock.tryLock(1,10, TimeUnit.SECONDS);
        if(isLock){
            try {
                System.out.println("获取成功执行业务");
            }finally {
                lock.unlock();
            }
        }
    }
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //按照typeid分组店铺至不同集合
        //for循环遍历或者使用下面的stream流
        Map<Long,List<Shop>> typemap = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for(Map.Entry<Long,List<Shop>> entry:typemap.entrySet()){
            //获取typeid
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY +typeId;
            //获取相同类型的店铺集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //写入redis
            for(Shop shop: value){
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

        //分批存储写入redis
    }
}
