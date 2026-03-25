package com.smartnote.service;

import com.smartnote.dto.UpdateUserProfileRequest;
import com.smartnote.dto.UserProfileResponse;
import com.smartnote.entity.User;
import com.smartnote.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class UserProfileService {

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(String username) {
        return toResponse(getUserByUsername(username));
    }

    @Transactional
    public UserProfileResponse updateCurrentUserProfile(String username, UpdateUserProfileRequest request) {
        User user = getUserByUsername(username);

        user.setNickname(normalizeNickname(request.getNickname(), user.getUsername()));
        user.setBio(normalizeText(request.getBio(), 500, "简介"));
        user.setPhone(normalizePhone(request.getPhone()));
        user.setBirthday(normalizeBirthday(request.getBirthday()));

        return toResponse(userRepository.save(user));
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                user.getBio(),
                user.getPhone(),
                user.getBirthday(),
                user.getRole()
        );
    }

    private String normalizeNickname(String nickname, String fallbackUsername) {
        String normalized = normalizeText(nickname, 50, "昵称");
        return normalized == null ? fallbackUsername : normalized;
    }

    private String normalizeText(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "长度不能超过" + maxLength + "个字符");
        }

        return trimmed;
    }

    private String normalizePhone(String phone) {
        String normalized = normalizeText(phone, 20, "手机号");
        if (normalized == null) {
            return null;
        }

        if (!normalized.matches("^[+0-9\\-\\s]{6,20}$")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }

        return normalized;
    }

    private LocalDate normalizeBirthday(LocalDate birthday) {
        if (birthday != null && birthday.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("生日不能晚于今天");
        }

        return birthday;
    }
}
