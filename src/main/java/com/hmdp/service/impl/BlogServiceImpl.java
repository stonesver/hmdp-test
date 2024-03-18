package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            //查询blog是否被点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByid(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("笔记不存在！");
        }
        //查询blog关联的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        this.isBlogLiked(blog);
        return Result.ok(blog);

    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户（不一定存在）
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.ok();
        }
        Long userId = user.getId();
        //判断当前用户是否点过赞，set集合判断是否存在
        String key = "blog:liked:"+ id ;
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        //未点赞
        if(score == null){
            //数据库点赞+1
            boolean res = update().setSql("liked = liked + 1").eq("id",id).update();
            //redis Zset集合添加，时间戳做score实现按点赞时间排序
            if(res){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            //点过赞，取消点赞
        } else {

            //数据库-1
            boolean res = update().setSql("liked = liked - 1").eq("id",id).update();
            //redis set集合移除
            if(res){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogSort(Long id) {
        //查询top5 点赞用户 zrange key
        String key = BLOG_LIKED_KEY+id;
        //解析用户id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0,4);
        //根据用户id查询用户
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //拼接查询的id列表
        String idStr = StrUtil.join(",",ids);
        //先处理，用beanutil拷贝成userDTO并重新收集成userDTO列表
        //SELECT id,phone,password,nick_name,icon,create_time,update_time FROM tb_user WHERE id IN ( 1010 , 1 )
        //该查询语句仍会按照升序返回，不符合要求，应使用order by
//        List<UserDTO> userDTOS = userService.listByIds(ids)
//                .stream()
//                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        //自己重写查询
        List<UserDTO> userDTOS = userService.query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")")
                .list()
                .stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog){
        //获取登录用户（不一定存在）
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否点过赞，set集合判断是否存在
        String key = "blog:liked:"+ blog.getId() ;
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(score != null);
    }
}
