package com.edupath.auth;

import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/access")
@Tag(name = "教师邀请码与评委链接")
public class AccountAccessAdminController {
    private final AccountAccessService access;
    private final RoleGuard roles;

    public AccountAccessAdminController(AccountAccessService access, RoleGuard roles) {
        this.access = access;
        this.roles = roles;
    }

    @PostMapping("/teacher-invites")
    public ApiResponse<AccountAccessService.IssuedInvite> issueTeacherInvite(
            @Valid @RequestBody TeacherInviteRequest request) {
        roles.requireAdmin();
        return ApiResponse.success(access.issueTeacherInvite(
                AuthContext.requirePrincipal().userId(), request.email(), hours(request.expiresInHours())));
    }

    @GetMapping("/review-links")
    public ApiResponse<AccountAccessService.ReviewLinks> listReviewLinks() {
        roles.requireAdmin();
        return ApiResponse.success(access.listReviewLinks());
    }

    @PostMapping("/review-links")
    public ApiResponse<AccountAccessService.IssuedReviewLink> issueReviewLink(
            @Valid @RequestBody ReviewLinkRequest request) {
        roles.requireAdmin();
        return ApiResponse.success(access.issueReviewLink(AuthContext.requirePrincipal().userId(), hours(request.expiresInHours())));
    }

    @DeleteMapping("/review-links/{id}")
    public ApiResponse<Void> revokeReviewLink(@PathVariable long id) {
        roles.requireAdmin();
        access.revokeReviewLink(id);
        return ApiResponse.success(null);
    }

    private int hours(Integer requestedHours) {
        return requestedHours == null ? 72 : requestedHours;
    }

    public record TeacherInviteRequest(@Email @Size(max = 255) String email,
                                       @Min(1) @Max(720) Integer expiresInHours) {}
    public record ReviewLinkRequest(@Min(1) @Max(720) Integer expiresInHours) {}
}
