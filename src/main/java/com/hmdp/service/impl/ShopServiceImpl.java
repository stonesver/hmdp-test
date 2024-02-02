package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.mysql.cj.util.TimeUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByid(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop=queryWithLock(id);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);

    }
    //缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key=CACHE_SHOP_KEY+id;
        //查询缓存
        String shopJSON =  stringRedisTemplate.opsForValue().get(key);
        //命中，转换成对象直接返回
        if(StrUtil.isNotBlank(shopJSON)){
            return JSONUtil.toBean(shopJSON,Shop.class);
        }
        //命中是空
        if(shopJSON==null){
            return null;
        }

        //未命中，查询数据库
        Shop shop=getById(id);
        //不存在，返回错误
        if(shop==null){
            //解决缓存穿透，使用空值写入缓存
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在，写入redis缓存，并返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    //缓存击穿
    public Shop queryWithLock(Long id) {
        String key=CACHE_SHOP_KEY+id;
        Shop shop = null;
        //查询缓存
        String shopJSON =  stringRedisTemplate.opsForValue().get(key);
        //命中，转换成对象直接返回
        if(StrUtil.isNotBlank(shopJSON)){
            return JSONUtil.toBean(shopJSON,Shop.class);
        }
        //命中是空
        if(shopJSON!=null){
            return null;
        }
        //缓存重建
        String lockKey = LOCK_SHOP_KEY+id;
        try {
            boolean isLock=tryLock(lockKey);
            //获取锁失败则递归重试
            if(!isLock){
                Thread.sleep(50);
                return queryWithLock(id);
            }
            //未命中，查询数据库
            shop=getById(id);
            //模拟延迟
            Thread.sleep(200);
            //不存在，返回错误
            if(shop==null){
                //解决缓存穿透，使用空值写入缓存
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在，写入redis缓存，并返回
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        return shop;
    }

    //声明自定义锁
    private boolean tryLock(String key){
        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.MINUTES);
        return BooleanUtil.isTrue(res);
    }
    private void unLock(String key){
       stringRedisTemplate.delete(key);
    }

    //事务控制缓存数据库一致性
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空！");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
