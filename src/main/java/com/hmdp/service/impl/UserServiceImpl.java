package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //失败
            return Result.fail("手机号无效");
        }
        //成功，生成验证码
        String Code = RandomUtil.randomNumbers(4);

        //保存session
        //session.setAttribute("code",Code);

        //保存在redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,Code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        //模拟发送，用日志代替
        log.debug(" 向手机号为：" + phone + "的用户" + "发送验证码："+Code);
        return Result.ok();
    }

    @Override
    public Result userLogin(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone =loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号无效");
        }
        Object cachecode =stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        //手机号正确，验证验证码
        if(cachecode==null||!cachecode.toString().equals(code)){
            //验证码无效
            return Result.fail("验证码无效");
        }
        //有效

        //查询用户是否存在
        User user = query().eq("phone",phone).one();

        //不存在，创建用户
        if (user==null){
            user= createUserWithPhone(phone);
        }

//        //保存session
//        //敏感信息不保存，使用新对象并进行拷贝
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        //保存在redis
        //UUID是否会生成过多的记录占用内存？
        //使用手机号是否可行？
        String token = UUID.randomUUID().toString();
        //转换成HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map;
        map = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true).setFieldValueEditor((fileName, fieldValue) -> fieldValue.toString()));
        //保存
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,map);
        //设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("stone_"+ RandomUtil.randomString(10));

        //保存用户
        save(user);
        return user;
    }
}
