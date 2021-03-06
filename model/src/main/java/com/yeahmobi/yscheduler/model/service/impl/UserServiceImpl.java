package com.yeahmobi.yscheduler.model.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.yeahmobi.yscheduler.common.Constants;
import com.yeahmobi.yscheduler.common.Paginator;
import com.yeahmobi.yscheduler.common.PasswordEncoder;
import com.yeahmobi.yscheduler.model.Team;
import com.yeahmobi.yscheduler.model.User;
import com.yeahmobi.yscheduler.model.UserExample;
import com.yeahmobi.yscheduler.model.common.NameValuePair;
import com.yeahmobi.yscheduler.model.dao.UserDao;
import com.yeahmobi.yscheduler.model.service.TeamService;
import com.yeahmobi.yscheduler.model.service.UserService;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserDao             userDao;

    @Autowired
    private TeamService         teamService;

    private static final String ADMIN = "admin";

    public User get(long id) {
        return this.userDao.selectByPrimaryKey(id);
    }

    public List<User> list(int pageNum, Paginator paginator) {
        UserExample example = new UserExample();

        example.setOrderByClause("create_time DESC");

        int count = this.userDao.countByExample(example);

        paginator.setItemsPerPage(Constants.PAGE_SIZE);
        paginator.setItems(count);
        paginator.setPage(pageNum);

        int offset = paginator.getBeginIndex() - 1;
        int limit = Constants.PAGE_SIZE;

        RowBounds rowBounds = new RowBounds(offset, limit);

        List<User> list = this.userDao.selectByExampleWithRowbounds(example, rowBounds);

        return list;
    }

    public List<User> listByTeam(long teamId, int pageNum, Paginator paginator) {
        UserExample example = new UserExample();
        example.createCriteria().andTeamIdEqualTo(teamId);
        example.setOrderByClause("create_time DESC");

        int count = this.userDao.countByExample(example);

        paginator.setItemsPerPage(Constants.PAGE_SIZE);
        paginator.setItems(count);
        paginator.setPage(pageNum);

        int offset = paginator.getBeginIndex() - 1;
        int limit = Constants.PAGE_SIZE;

        RowBounds rowBounds = new RowBounds(offset, limit);

        List<User> list = this.userDao.selectByExampleWithRowbounds(example, rowBounds);

        return list;
    }

    public List<NameValuePair> list() {
        UserExample example = new UserExample();
        List<User> users = this.userDao.selectByExample(example);
        List<NameValuePair> result = new ArrayList<NameValuePair>();
        for (User user : users) {
            NameValuePair pair = new NameValuePair();
            pair.setValue(user.getId());
            pair.setName(user.getName());
            result.add(pair);
        }
        return result;
    }

    public User get(String username) {
        UserExample example = new UserExample();
        example.createCriteria().andNameEqualTo(username);
        List<User> users = this.userDao.selectByExample(example);
        if (users.isEmpty()) {
            throw new IllegalArgumentException(String.format("用户 %s 不存在", username));
        } else {
            return users.get(0);
        }
    }

    private boolean nameExists(String name) {
        UserExample example = new UserExample();
        example.createCriteria().andNameEqualTo(name);
        return this.userDao.selectByExample(example).size() != 0;
    }

    public void add(User user) {
        if (nameExists(user.getName())) {
            throw new IllegalArgumentException(String.format("User %s 已经存在", user.getName()));
        }
        Date time = new Date();
        user.setCreateTime(time);
        user.setUpdateTime(time);
        this.userDao.insertSelective(user);
    }

    public void remove(long userId) {
        this.userDao.deleteByPrimaryKey(userId);
    }

    public void resetPassword(long userId) {
        User user = this.userDao.selectByPrimaryKey(userId);
        User record = new User();
        record.setId(userId);
        record.setUpdateTime(new Date());
        record.setPassword(PasswordEncoder.encode(user.getName()));
        this.userDao.updateByPrimaryKeySelective(record);
    }

    public void update(User user) {
        this.userDao.updateByPrimaryKeySelective(user);
    }

    public void regenToken(long userId) {
        User user = this.userDao.selectByPrimaryKey(userId);
        if (user == null) {
            throw new IllegalArgumentException(String.format("UserId(%s) 不存在存在", userId));
        }
        User record = new User();
        record.setId(userId);
        record.setToken(UUID.randomUUID().toString());
        record.setUpdateTime(new Date());
        this.userDao.updateByPrimaryKeySelective(record);
    }

    public boolean hasTeamUser(long teamId) {
        UserExample example = new UserExample();
        example.createCriteria().andTeamIdEqualTo(teamId);

        List<User> users = this.userDao.selectByExample(example);
        return (users != null) && !users.isEmpty();
    }

    public List<User> listByTeam(long teamId) {
        UserExample example = new UserExample();
        example.createCriteria().andTeamIdEqualTo(teamId);
        example.setOrderByClause("create_time DESC");

        return this.userDao.selectByExample(example);

    }

    public String getTeamName(long id) {
        User user = get(id);
        if ((user == null) || (user.getTeamId() == null)) {
            return null;
        }
        Team team = this.teamService.get(user.getTeamId());
        return team == null ? null : team.getName();
    }

    public User getAdmin() {
        return get(ADMIN);
    }

}
