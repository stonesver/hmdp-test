package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheCilent;
import com.hmdp.utils.SystemConstants;
import com.mysql.cj.util.TimeUtil;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;
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

    @Resource
    private CacheCilent cacheCilent;

    @Override
    public Result queryByid(Long id) {
        //缓存穿透
        //使用封装好的工具类
        Shop shop = cacheCilent.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop=queryWithLock(id);
        //Shop shop = cacheCilent.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,20L,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);

    }
    //缓存穿透，已被封装成RedisCilent中的工具
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
//    public Shop queryWithLock(Long id) {
//        String key=CACHE_SHOP_KEY+id;
//        Shop shop = null;
//        //查询缓存
//        String shopJSON =  stringRedisTemplate.opsForValue().get(key);
//        //命中，转换成对象直接返回
//        if(StrUtil.isNotBlank(shopJSON)){
//            return JSONUtil.toBean(shopJSON,Shop.class);
//        }
//        //命中是空
//        if(shopJSON!=null){
//            return null;
//        }
//        //缓存重建
//        String lockKey = LOCK_SHOP_KEY+id;
//        try {
//            boolean isLock=tryLock(lockKey);
//            //获取锁失败则递归重试
//            if(!isLock){
//                Thread.sleep(50);
//                return queryWithLock(id);
//            }
//            //未命中，查询数据库
//            shop=getById(id);
//            //模拟延迟
//            Thread.sleep(200);
//            //不存在，返回错误
//            if(shop==null){
//                //解决缓存穿透，使用空值写入缓存
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //数据库存在，写入redis缓存，并返回
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放锁
//            unLock(lockKey);
//        }
//        return shop;
//    }

//    //声明自定义锁
//    private boolean tryLock(String key){
//        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.MINUTES);
//        return BooleanUtil.isTrue(res);
//    }
//    private void unLock(String key){
//       stringRedisTemplate.delete(key);
//    }

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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要按照坐标查询
        if(x==null||y==null){
            //按照数据库查
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1 ) *SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current *SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis、按照距离排序、分页
        //5km范围内
        String key = SHOP_GEO_KEY + typeId;
        //redis版本需在6.0以上
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()//GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        //解析得到id
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //截取分页部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //获取id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询店铺
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
