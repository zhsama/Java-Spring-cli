package com.zl.mjga.service;

import static org.jooq.generated.tables.Permission.PERMISSION;
import static org.jooq.generated.tables.Role.ROLE;
import static org.jooq.generated.tables.User.USER;

import com.zl.mjga.dto.PageRequestDto;
import com.zl.mjga.dto.PageResponseDto;
import com.zl.mjga.dto.urp.*;
import com.zl.mjga.exception.BusinessException;
import com.zl.mjga.model.urp.ERole;
import com.zl.mjga.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.generated.tables.pojos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserRolePermissionService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleMapRepository userRoleMapRepository;
  private final PermissionRepository permissionRepository;
  private final RolePermissionMapRepository rolePermissionMapRepository;

  public PageResponseDto<List<UserRolePermissionDto>> pageQueryUser(
      PageRequestDto pageRequestDto, UserQueryDto userQueryDto) {
    Result<Record> userRecords = userRepository.pageFetchBy(pageRequestDto, userQueryDto);
    if (userRecords.isEmpty()) {
      return PageResponseDto.empty();
    }
    List<UserRolePermissionDto> userRolePermissionDtoList =
        userRecords.stream()
            .map((record) -> queryUniqueUserWithRolePermission(record.getValue(USER.ID)).get())
            .toList();
    return new PageResponseDto<>(
        userRecords.get(0).getValue("total_user", Integer.class), userRolePermissionDtoList);
  }

  public Optional<UserRolePermissionDto> queryUniqueUserWithRolePermission(Long userId) {
    UserRolePermissionDto records =
        userRepository.fetchUniqueUserDtoWithNestedRolePermissionBy(userId);
    if (records == null) {
      return Optional.empty();
    } else {
      return Optional.of(records);
    }
  }

  public PageResponseDto<List<RoleDto>> pageQueryRole(
      PageRequestDto pageRequestDto, RoleQueryDto roleQueryDto) {
    if (roleQueryDto.getUserId() != null) {
      List<Long> roleIdList =
          userRoleMapRepository.fetchByUserId(roleQueryDto.getUserId()).stream()
              .map(UserRoleMap::getRoleId)
              .toList();
      if (roleIdList.isEmpty()) {
        return PageResponseDto.empty();
      } else {
        roleQueryDto.setRoleIdList(roleIdList);
      }
    }
    Result<Record> roleRecords = roleRepository.pageFetchBy(pageRequestDto, roleQueryDto);
    if (roleRecords.isEmpty()) {
      return PageResponseDto.empty();
    }
    List<RoleDto> roleDtoList =
        roleRecords.stream()
            .map(record -> queryUniqueRoleWithPermission(record.getValue(ROLE.ID)).get())
            .toList();
    return new PageResponseDto<>(
        roleRecords.get(0).getValue("total_role", Integer.class), roleDtoList);
  }

  public Optional<RoleDto> queryUniqueRoleWithPermission(Long roleId) {
    Result<Record> roleWithPermissionRecords = roleRepository.fetchUniqueRoleWithPermission(roleId);
    if (roleWithPermissionRecords.isEmpty()) {
      return Optional.empty();
    }
    RoleDto roleDto = createRbacDtoRolePart(roleWithPermissionRecords);
    setCurrentRolePermission(roleDto, roleWithPermissionRecords);
    return Optional.of(roleDto);
  }

  public PageResponseDto<List<PermissionDto>> pageQueryPermission(
      PageRequestDto pageRequestDto, PermissionQueryDto permissionQueryDto) {
    if (permissionQueryDto.getRoleId() != null) {
      List<Long> permissionIdList =
          rolePermissionMapRepository.fetchByRoleId(permissionQueryDto.getRoleId()).stream()
              .map(RolePermissionMap::getPermissionId)
              .toList();
      if (permissionIdList.isEmpty()) {
        return PageResponseDto.empty();
      } else {
        permissionQueryDto.setPermissionIdList(permissionIdList);
      }
    }
    Result<Record> permissionRecords =
        permissionRepository.pageFetchBy(pageRequestDto, permissionQueryDto);
    if (permissionRecords.isEmpty()) {
      return PageResponseDto.empty();
    }
    List<PermissionDto> permissionDtoList =
        permissionRecords.into(Permission.class).stream()
            .map(pojo -> new PermissionDto(pojo.getId(), pojo.getName(), pojo.getCode()))
            .toList();
    return new PageResponseDto<>(
        permissionRecords.get(0).getValue("total_permission", Integer.class), permissionDtoList);
  }

  @Transactional(rollbackFor = Throwable.class)
  public void bindPermissionToRole(Long roleId, List<Long> permissionIdList) {
    rolePermissionMapRepository.deleteByRoleId(roleId);
    if (CollectionUtils.isEmpty(permissionIdList)) {
      return;
    }
    List<Permission> permissions = permissionRepository.selectByPermissionIdIn(permissionIdList);
    if (CollectionUtils.isEmpty(permissions)) {
      throw new BusinessException("bind permission not exist");
    }
    List<RolePermissionMap> permissionMapList =
        permissions.stream()
            .map(
                (permission -> {
                  RolePermissionMap rolePermissionMap = new RolePermissionMap();
                  rolePermissionMap.setRoleId(roleId);
                  rolePermissionMap.setPermissionId(permission.getId());
                  return rolePermissionMap;
                }))
            .collect(Collectors.toList());
    rolePermissionMapRepository.insert(permissionMapList);
  }

  @Transactional(rollbackFor = Throwable.class)
  public void bindRoleToUser(Long userId, List<Long> roleIdList) {
    userRoleMapRepository.deleteByUserId(userId);
    if (CollectionUtils.isEmpty(roleIdList)) {
      return;
    }
    List<Role> roles = roleRepository.selectByRoleIdIn(roleIdList);
    if (CollectionUtils.isEmpty(roles)) {
      throw new BusinessException("bind role not exist");
    }
    List<UserRoleMap> userRoleMapList =
        roles.stream()
            .map(
                (role -> {
                  UserRoleMap userRoleMap = new UserRoleMap();
                  userRoleMap.setUserId(userId);
                  userRoleMap.setRoleId(role.getId());
                  return userRoleMap;
                }))
            .collect(Collectors.toList());
    userRoleMapRepository.insert(userRoleMapList);
  }

  @Transactional(rollbackFor = Throwable.class)
  public void bindRoleModuleToUser(Long userId, List<ERole> eRoleList) {
    bindRoleToUser(
        userId,
        roleRepository
            .selectByRoleCodeIn(eRoleList.stream().map(Enum::name).collect(Collectors.toList()))
            .stream()
            .map(Role::getId)
            .toList());
  }

  private void setCurrentRolePermission(RoleDto roleDto, List<Record> roleResult) {
    if (roleResult.get(0).getValue(PERMISSION.ID) != null) {
      roleResult.forEach(
          (record) -> {
            PermissionDto permissionDto = createRbacDtoPermissionPart(record);
            roleDto.getPermissions().add(permissionDto);
          });
    }
  }

  private PermissionDto createRbacDtoPermissionPart(Record record) {
    PermissionDto permissionDto = new PermissionDto();
    permissionDto.setId(record.getValue(PERMISSION.ID));
    permissionDto.setCode(record.getValue(PERMISSION.CODE));
    permissionDto.setName(record.getValue(PERMISSION.NAME));
    return permissionDto;
  }

  private RoleDto createRbacDtoRolePart(List<Record> roleResult) {
    RoleDto roleDto = new RoleDto();
    roleDto.setId(roleResult.get(0).getValue(ROLE.ID));
    roleDto.setCode(roleResult.get(0).getValue(ROLE.CODE));
    roleDto.setName(roleResult.get(0).getValue(ROLE.NAME));
    return roleDto;
  }
}
