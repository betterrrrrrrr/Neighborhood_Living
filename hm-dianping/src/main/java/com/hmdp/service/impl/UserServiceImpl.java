package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.dto.UserDTO;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.若不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码   set key value ex 120
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到 redis
        // session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.info("发送短信验证码成功，验证码:{}",code);
        // 6.返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //若不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 2.从redis校验验证码
        String code = loginForm.getCode();
        // Object cachecode = session.getAttribute("code");
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        // 3.不一致，报错
        if (cachecode == null || !cachecode.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 4.根据手机号查询用户  select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = creatUserWithPhone(phone);
        }
        // 7. 将 User 对象转换为 UserDTO 对象，隐藏敏感信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 8.保存用户信息到redis中
        // 8.1随机生成token,作为登陆令牌
        String token = UUID.randomUUID().toString();
        // 8.3将user对象转换成hash存储LOGIN_CODE_TTL
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new java.util.HashMap<>(),
                cn.hutool.core.bean.copier.CopyOptions.create()
                        .setIgnoreNullValue(true)
                        // 【修复】：加入 null 值校验，防止 fieldValue.toString() 报空指针
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString()
                        ));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token, userMap);
        // 8.4存储后设置有效期返回token给客户端
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);

        // session.setAttribute("user",userDTO);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis  SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天的签到记录,返回十进制数字. BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null||result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num==null || num==0) {
            return Result.ok(0);
        }
        //循环遍历，与1进行与运算，得到最后一位是0或1，即可判断该天是否签到了
        int count = 0;
        while(true){
            if((num & 1) == 0){
                //为0，未签到，结束
                break;
            }else{
                //不为1，已签到，计数器+1
                count++;
            }
            //数字右移，抛弃最后一位
            num >>>=1;
        }

        return Result.ok(count);
    }

    private User creatUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        // 2.保存用户
        save(user);

        return user;
    }
}
