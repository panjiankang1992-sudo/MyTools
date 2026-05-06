package com.yuyutian.mytools.role.service.impl;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.role.mapper.RoleMapper;
import com.yuyutian.mytools.role.model.Role;
import com.yuyutian.mytools.role.model.RoleRequest;
import com.yuyutian.mytools.role.model.RoleResponse;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色服务实现类。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Slf4j
@Service
public class RoleServiceImpl implements com.yuyutian.mytools.role.service.RoleService {

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public RoleServiceImpl(RoleMapper roleMapper, UserMapper userMapper,
                          SnowflakeIdGenerator snowflakeIdGenerator) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Override
    public List<RoleResponse> getRoleList() {
        List<Role> roles = roleMapper.findAll();
        return roles.stream().map(this::convertToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RoleResponse createRole(RoleRequest request, Long adminUserId) {
        // 验证管理员身份
        validateAdmin(adminUserId);

        // 检查角色编码唯一性
        if (roleMapper.findByRoleCode(request.getRoleCode()) != null) {
            throw new BusinessException(ErrorCode.SYS_003, "角色编码已存在");
        }

        // 生成雪花ID
        long roleId = snowflakeIdGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();

        // 构建角色对象
        Role role = new Role();
        role.setId(roleId);
        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode());
        role.setDescription(request.getDescription());
        role.setStatus("ACTIVE");
        role.setCreateTime(now);
        role.setUpdateTime(now);

        // 保存角色
        roleMapper.insert(role);

        log.info("管理员{}创建了新角色{}", adminUserId, roleId);

        return convertToResponse(role);
    }

    @Override
    @Transactional
    public RoleResponse updateRole(Long roleId, RoleRequest request, Long adminUserId) {
        // 验证管理员身份
        validateAdmin(adminUserId);

        // 检查角色是否存在
        Role role = roleMapper.findById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 如果更新角色编码，检查唯一性
        if (request.getRoleCode() != null && !request.getRoleCode().equals(role.getRoleCode())) {
            Role existingRole = roleMapper.findByRoleCode(request.getRoleCode());
            if (existingRole != null) {
                throw new BusinessException(ErrorCode.SYS_003, "角色编码已存在");
            }
        }

        // 更新角色（只更新非空字段）
        if (request.getRoleName() != null) {
            role.setRoleName(request.getRoleName());
        }
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            role.setStatus(request.getStatus());
        }
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.update(role);

        log.info("管理员{}更新了角色{}", adminUserId, roleId);

        return convertToResponse(role);
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId, Long adminUserId) {
        // 验证管理员身份
        validateAdmin(adminUserId);

        // 检查角色是否存在
        Role role = roleMapper.findById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }

        // 检查角色是否已分配给用户
        if (roleMapper.countByRoleId(roleId) > 0) {
            throw new BusinessException(ErrorCode.SYS_003, "该角色已分配给用户，无法删除");
        }

        // 删除角色
        roleMapper.deleteById(roleId);

        log.info("管理员{}删除了角色{}", adminUserId, roleId);
    }

    /**
     * 验证管理员身份。
     */
    private void validateAdmin(Long adminUserId) {
        var admin = userMapper.findById(adminUserId);
        if (admin == null) {
            throw new BusinessException(ErrorCode.USER_001);
        }
        if (!"ADMIN".equals(admin.getRole())) {
            throw new BusinessException(ErrorCode.AUTH_003);
        }
    }

    private RoleResponse convertToResponse(Role role) {
        RoleResponse response = new RoleResponse();
        response.setId(role.getId());
        response.setRoleName(role.getRoleName());
        response.setRoleCode(role.getRoleCode());
        response.setDescription(role.getDescription());
        response.setStatus(role.getStatus());
        return response;
    }
}
