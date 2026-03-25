package com.smartnote.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateUserProfileRequest {
    private String nickname;
    private String bio;
    private String phone;
    private LocalDate birthday;
}
