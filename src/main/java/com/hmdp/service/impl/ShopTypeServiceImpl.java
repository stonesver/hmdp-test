package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key=CACHE_SHOP_KEY+"type";
        //查询缓存
        List<String> typeList =  stringRedisTemplate.opsForList().range(key,0,-1);
        //命中，转换成对象直接返回
        if(!typeList.isEmpty()){
            List<ShopType> shopTypeList = JSONUtil.toList(typeList.toString(),ShopType.class);
            return Result.ok(shopTypeList);
        }
        //未命中，查询数据库
        List<ShopType> shopTypeList= query().orderByAsc("sort").list();
        //不存在，返回错误
        if(shopTypeList==null){
            return Result.fail("店铺类型列表出错！");
        }
        //数据库存在，写入redis缓存，并返回
        //将对象转换成json字符串，写入redis
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForList().rightPush(key,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(shopTypeList);
    }
}
