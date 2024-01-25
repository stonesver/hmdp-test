package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        String key=CACHE_SHOP_KEY+id;
        //查询缓存
        String shopJSON =  stringRedisTemplate.opsForValue().get(key);
        System.out.println("222");
        //命中，转换成对象直接返回
        if(StrUtil.isNotBlank(shopJSON)){
            Shop shop = JSONUtil.toBean(shopJSON,Shop.class);
            return Result.ok(shop);
        }
        //未命中，查询数据库
        Shop shop=getById(id);
        //不存在，返回错误
        if(shop==null){
            return Result.fail("店铺不存在！");
        }
        //数据库存在，写入redis缓存，并返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);

    }
}
