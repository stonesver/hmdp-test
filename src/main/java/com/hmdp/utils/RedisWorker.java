package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    //设定开始时间戳
    private static final long BEGIN_TIMESTAMP= 1640995200L;
    //序列号位数
    private static final int COUNT_BITS=32;

    private StringRedisTemplate stringRedisTemplate;
    public RedisWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    public long nextId(String keyPrefix){

        //符号位1+时间戳31+序列号32

        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp= nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号，使用redis自增
        //同一业务前缀相同，但是使用当天的日期拼接防止超出上限，同时方便后台统计
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+data);

        //3.返回long，借助位运算
        //左移32空出右边再用或运算（0|1=1）加上序列号
        return timestamp << COUNT_BITS | count;
    }
}
