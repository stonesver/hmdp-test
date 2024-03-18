package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
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
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否点过赞，set集合判断是否存在
        String key = "blog:liked:"+ id ;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key,userId.toString());
        //未点赞
        if(BooleanUtil.isFalse(isMember)){
            //数据库点赞+1
            boolean res = update().setSql("liked = liked + 1").eq("id",id).update();
            //redis set集合添加
            if(res){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
            //点过赞，取消点赞
        } else {

            //数据库-1
            boolean res = update().setSql("liked = liked - 1").eq("id",id).update();
            //redis set集合移除
            if(res){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog){
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否点过赞，set集合判断是否存在
        String key = "blog:liked:"+ blog.getId() ;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key,userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }
}
