package com.smartnote.controller;

import com.smartnote.dto.AdminOverviewResponse;
import com.smartnote.dto.AdminSearchMaintenanceResponse;
import com.smartnote.dto.AdminStorageOverviewResponse;
import com.smartnote.dto.AdminUserStorageResponse;
import com.smartnote.dto.AdminUserSummaryResponse;
import com.smartnote.dto.UpdateAdminUserRoleRequest;
import com.smartnote.dto.UpdateAdminUserStatusRequest;
import com.smartnote.service.AdminService;
import com.smartnote.service.AdminSearchMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final AdminSearchMaintenanceService adminSearchMaintenanceService;

    public AdminController(
            AdminService adminService,
            AdminSearchMaintenanceService adminSearchMaintenanceService
    ) {
        this.adminService = adminService;
        this.adminSearchMaintenanceService = adminSearchMaintenanceService;
    }

    @GetMapping("/overview")
    public ResponseEntity<AdminOverviewResponse> getOverview() {
        return ResponseEntity.ok(adminService.getOverview());
    }

    @GetMapping("/storage/overview")
    public ResponseEntity<AdminStorageOverviewResponse> getStorageOverview() {
        return ResponseEntity.ok(adminService.getStorageOverview());
    }

    @GetMapping("/search/maintenance")
    public ResponseEntity<AdminSearchMaintenanceResponse> getSearchMaintenanceOverview() {
        return ResponseEntity.ok(adminSearchMaintenanceService.getOverview());
    }

    @PostMapping("/search/maintenance/{operation}")
    public ResponseEntity<?> runSearchMaintenance(@PathVariable String operation) {
        try {
            return ResponseEntity.ok(adminSearchMaintenanceService.runMaintenance(operation));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role
    ) {
        try {
            List<AdminUserSummaryResponse> users = adminService.listUsers(keyword, status, role);
            return ResponseEntity.ok(users);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/storage/users")
    public ResponseEntity<?> listUserStorage(@RequestParam(required = false) String keyword) {
        try {
            List<AdminUserStorageResponse> users = adminService.listUserStorage(keyword);
            return ResponseEntity.ok(users);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/users/{userId}/status")
    public ResponseEntity<?> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody UpdateAdminUserStatusRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(adminService.updateUserStatus(userId, request, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PutMapping("/users/{userId}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long userId,
            @RequestBody UpdateAdminUserRoleRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(adminService.updateUserRole(userId, request, authentication.getName()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }
}
