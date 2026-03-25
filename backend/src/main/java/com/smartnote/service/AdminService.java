package com.smartnote.service;

import com.smartnote.dto.AdminOverviewResponse;
import com.smartnote.dto.AdminUserSummaryResponse;
import com.smartnote.dto.UpdateAdminUserRoleRequest;
import com.smartnote.dto.UpdateAdminUserStatusRequest;
import com.smartnote.entity.User;
import com.smartnote.repository.NoteRepository;
import com.smartnote.repository.NotebookRepository;
import com.smartnote.repository.TagRepository;
import com.smartnote.repository.UserRepository;
import com.smartnote.repository.projection.UserOwnedCountProjection;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AdminService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String STATUS_ALL = "ALL";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String TRASH_STATUS = "TRASH";

    private final UserRepository userRepository;
    private final NoteRepository noteRepository;
    private final NotebookRepository notebookRepository;
    private final TagRepository tagRepository;

    public AdminService(
            UserRepository userRepository,
            NoteRepository noteRepository,
            NotebookRepository notebookRepository,
            TagRepository tagRepository
    ) {
        this.userRepository = userRepository;
        this.noteRepository = noteRepository;
        this.notebookRepository = notebookRepository;
        this.tagRepository = tagRepository;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse getOverview() {
        return new AdminOverviewResponse(
                userRepository.count(),
                userRepository.countByIsActiveTrue(),
                userRepository.countByIsActiveFalse(),
                userRepository.countByRole(ROLE_ADMIN),
                noteRepository.countByStatusNot(TRASH_STATUS),
                notebookRepository.countByStatusNot(TRASH_STATUS),
                tagRepository.count()
        );
    }

    @Transactional(readOnly = true)
    public List<AdminUserSummaryResponse> listUsers(String keyword, String status, String role) {
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedStatus = normalizeStatus(status);
        String normalizedRole = normalizeRole(role);

        Map<Long, Long> noteCounts = toCountMap(noteRepository.countByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> notebookCounts = toCountMap(notebookRepository.countByUserIdGroupedExcludingStatus(TRASH_STATUS));
        Map<Long, Long> tagCounts = toCountMap(tagRepository.countByUserIdGrouped());

        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter((user) -> matchesKeyword(user, normalizedKeyword))
                .filter((user) -> matchesStatus(user, normalizedStatus))
                .filter((user) -> matchesRole(user, normalizedRole))
                .map((user) -> toResponse(user, noteCounts, notebookCounts, tagCounts))
                .toList();
    }

    @Transactional
    public AdminUserSummaryResponse updateUserStatus(Long userId, UpdateAdminUserStatusRequest request, String actorUsername) {
        if (request.getActive() == null) {
            throw new IllegalArgumentException("用户启用状态不能为空");
        }

        User actor = getUserByUsername(actorUsername);
        User target = getUserById(userId);

        if (!request.getActive()) {
            if (Objects.equals(actor.getId(), target.getId())) {
                throw new IllegalArgumentException("不能禁用当前登录管理员");
            }

            if (isAdmin(target) && target.isActive() && userRepository.countByRoleAndIsActiveTrue(ROLE_ADMIN) <= 1) {
                throw new IllegalArgumentException("系统至少需要保留一个启用中的管理员");
            }
        }

        target.setActive(request.getActive());
        return toResponse(userRepository.save(target));
    }

    @Transactional
    public AdminUserSummaryResponse updateUserRole(Long userId, UpdateAdminUserRoleRequest request, String actorUsername) {
        String nextRole = normalizeManagedRole(request.getRole());
        User actor = getUserByUsername(actorUsername);
        User target = getUserById(userId);
        String currentRole = normalizeStoredRole(target.getRole());

        if (Objects.equals(actor.getId(), target.getId()) && ROLE_USER.equals(nextRole)) {
            throw new IllegalArgumentException("不能将当前登录管理员降级为普通用户");
        }

        if (ROLE_ADMIN.equals(currentRole) && ROLE_USER.equals(nextRole) && target.isActive()
                && userRepository.countByRoleAndIsActiveTrue(ROLE_ADMIN) <= 1) {
            throw new IllegalArgumentException("系统至少需要保留一个启用中的管理员");
        }

        target.setRole(nextRole);
        return toResponse(userRepository.save(target));
    }

    private Map<Long, Long> toCountMap(List<UserOwnedCountProjection> projections) {
        return projections.stream().collect(Collectors.toMap(
                UserOwnedCountProjection::getUserId,
                UserOwnedCountProjection::getTotal
        ));
    }

    private AdminUserSummaryResponse toResponse(
            User user,
            Map<Long, Long> noteCounts,
            Map<Long, Long> notebookCounts,
            Map<Long, Long> tagCounts
    ) {
        long userId = user.getId();
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                normalizeStoredRole(user.getRole()),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                noteCounts.getOrDefault(userId, 0L),
                notebookCounts.getOrDefault(userId, 0L),
                tagCounts.getOrDefault(userId, 0L)
        );
    }

    private AdminUserSummaryResponse toResponse(User user) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                normalizeStoredRole(user.getRole()),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                noteRepository.countByNotebookUserIdAndStatusNot(user.getId(), TRASH_STATUS),
                notebookRepository.countByUserIdAndStatusNot(user.getId(), TRASH_STATUS),
                tagRepository.countByUserId(user.getId())
        );
    }

    private boolean matchesKeyword(User user, String keyword) {
        if (keyword == null) {
            return true;
        }

        return Stream.of(user.getUsername(), user.getEmail(), user.getNickname())
                .filter(Objects::nonNull)
                .map((value) -> value.toLowerCase(Locale.ROOT))
                .anyMatch((value) -> value.contains(keyword));
    }

    private boolean matchesStatus(User user, String status) {
        return switch (status) {
            case STATUS_ACTIVE -> user.isActive();
            case STATUS_INACTIVE -> !user.isActive();
            default -> true;
        };
    }

    private boolean matchesRole(User user, String role) {
        if (STATUS_ALL.equals(role)) {
            return true;
        }

        return normalizeStoredRole(user.getRole()).equals(role);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return STATUS_ALL;
        }

        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_ALL, STATUS_ACTIVE, STATUS_INACTIVE).contains(normalized)) {
            throw new IllegalArgumentException("不支持的用户状态筛选");
        }

        return normalized;
    }

    private String normalizeRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            return STATUS_ALL;
        }

        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_ALL, ROLE_USER, ROLE_ADMIN).contains(normalized)) {
            throw new IllegalArgumentException("不支持的角色筛选");
        }

        return normalized;
    }

    private String normalizeManagedRole(String role) {
        String normalized = normalizeRole(role);
        if (STATUS_ALL.equals(normalized)) {
            throw new IllegalArgumentException("用户角色不能为空");
        }
        return normalized;
    }

    private String normalizeStoredRole(String role) {
        if (role == null || role.isBlank()) {
            return ROLE_USER;
        }

        return role.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isAdmin(User user) {
        return ROLE_ADMIN.equals(normalizeStoredRole(user.getRole()));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("当前管理员不存在"));
    }
}
