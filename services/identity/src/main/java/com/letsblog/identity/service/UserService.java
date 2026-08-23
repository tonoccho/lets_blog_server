package com.letsblog.identity.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UpdateGithubTokenRequest;
import com.letsblog.identity.dto.UpdateUserPreferencesRequest;
import com.letsblog.identity.dto.UserCreateRequest;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.dto.UserProfileUpdateRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ユーザーのCRUD・プロフィール管理を担う(#561)。ログイン(パスワード照合・2FA・APIキー発行)は
 * legacy-apiのUserServiceに残したまま(現行の認証機構はカットオーバー計画(#591)を経てから
 * 撤去する方針。ADR-0003参照)であり、このクラスでは扱わない。
 *
 * <p>現時点ではlegacy-apiと同一の物理スキーマ(lets_blog)上のusers/rolesテーブルを
 * 参照する(ADR-0004が求めるスキーマ分離自体は本Issueのスコープ外。両サービスが
 * 同一テーブルを直接参照する状態は移行期間中の暫定措置であり、将来のスキーマ分離
 * Issueで解消する)。
 */
@Service
public class UserService {

    private static final Set<String> VALID_ROLES = Set.of("admin", "user");

    private static final Map<String, String> LEGACY_ROLE_TO_ROLE_NAME = Map.of(
            "admin", "ROLE_ADMIN",
            "user", "ROLE_VIEWER");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CredentialCipher credentialCipher;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepository, RoleRepository roleRepository, CredentialCipher credentialCipher) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.credentialCipher = credentialCipher;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("メールアドレス '" + request.email() + "' は既に登録されています");
        }
        validateRole(request.role());

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());

        String defaultRoleName = LEGACY_ROLE_TO_ROLE_NAME.get(request.role());
        if (defaultRoleName != null) {
            roleRepository.findByRoleName(defaultRoleName).ifPresent(role -> user.getRoles().add(role));
        }

        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        if (request.role() != null) {
            validateRole(request.role());
            user.setRole(request.role());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        return UserResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse findUserWithProfile(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));
        return UserProfileResponse.from(user);
    }

    @Transactional
    public UserProfileResponse updateUserProfile(Long id, UserProfileUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setDisplayName(request.displayName());
        user.setNickname(request.nickname());
        user.setWebsiteUrl(request.websiteUrl());
        user.setBio(request.bio());
        user.setLocale(request.locale());
        user.setAvatarUrl(request.avatarUrl());
        user.setDepartment(request.department());
        user.setPosition(request.position());
        user.setSocialLinks(request.socialLinks());
        user.setCustomLinks(request.customLinks());

        return UserProfileResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserProfileResponse updateUserPreferences(Long id, UpdateUserPreferencesRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("不正なタイムゾーンです: " + request.timezone());
        }

        user.setLocale(request.locale());
        user.setTimezone(request.timezone());

        return UserProfileResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserProfileResponse updateGithubToken(Long id, UpdateGithubTokenRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("id " + id + " のユーザーは登録されていません"));

        byte[] encrypted = credentialCipher.encrypt(request.githubToken());
        user.setGithubTokenEncrypted(encrypted);

        return UserProfileResponse.from(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException("id " + id + " のユーザーは登録されていません");
        }
        userRepository.deleteById(id);
    }

    private void validateRole(String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidRoleException("role は 'admin' または 'user' である必要があります");
        }
    }
}
