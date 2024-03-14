package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    private static final String KEY_PREFIX="lock:";
    //用UUID标识当前jvm
    private static final String ID_PREFIX= UUID.randomUUID().toString()+"-";

    //获取锁
    @Override
    public boolean tryLock(long timeoutSec) {
        //UUID拼接线程id
        String threadId = ID_PREFIX+Thread.currentThread().getId();

        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId+"",timeoutSec, TimeUnit.SECONDS);
        //取消装箱可能导致null风险
        return Boolean.TRUE.equals(res);
    }

    //释放锁
    @Override
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁的标识（redis中存的）
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX);
        if(threadId.equals(id)){
            //相同才释放
            //极端情况下，有可能jvm内部进行垃圾会回收等阻塞在此处
            //即判断完成但还没释放锁，仍可能发生阻塞,需要保证两个操作的原子性才行,可以使用lua脚本,这里不演示
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }
}
