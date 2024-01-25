package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshInterceptor implements HandlerInterceptor {

    //此处不负责拦截，只负责刷新


    //不能使用注入，除非将该类交给spring管理再让webconfig注入
    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate=redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //获取session
//        HttpSession session = request.getSession();
//        //获取用户
//        Object user = session.getAttribute("user");

        //获取请求头中的token
        String token =request.getHeader("authorization");
        //基于token获取redis的用户
        String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
        Map<Object,Object> map= stringRedisTemplate.opsForHash()
                .entries(tokenKey);
        //存在
        //将查询的Hash转换为UserDTO对象
        System.out.println(111);
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map,new UserDTO(),false);
        // 保存用户到ThreadLocal
        UserHolder.saveUser(userDTO);

        //刷新有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
