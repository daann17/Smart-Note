package com.smartnote.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class UserProfileResponse {
    private String username;
    private String email;
    private String nickname;
    private String bio;
    private String phone;
    private LocalDate birthday;
    private String role;
}
