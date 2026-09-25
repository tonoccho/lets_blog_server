package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * issue #1241: プロフィール編集画面からのアバターアップロードのオーケストレーション
 * (形式検証 -> 画像処理(512x512化・メタ情報削除) -> 専用ボリュームへの保存 -> avatar_urlの更新)。
 *
 * <p>{@code users.avatar_url}列は既存の「アバターURL(Gravatar等)」テキスト入力欄
 * ({@code UserProfileUpdateRequest#avatarUrl}経由の{@code updateUserProfile})と共有する
 * (issue #1241 Out of Scope「アバターURLテキスト入力欄の廃止・移行は行わない」)。
 * アップロード成功時にこの列をこのAPIが配信するURL({@code /api/users/{id}/avatar})へ書き換えることで、
 * 既存の表示経路(UserProfileForm.tsxの{@code <img src={profile.avatarUrl}>})をそのまま再利用できる。
 * どちらの方法で設定しても最終的に同じ列に収まるため、後から書き込んだ方が有効になる
 * (両立であって、履歴の保持や優先順位付けはこのIssueのスコープ外)。
 */
@Service
public class AvatarService {

    /** issue #1241 要件4: 受理する形式。GIF・SVGを含むそれ以外は拒否する。 */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository userRepository;
    private final AvatarImageProcessor avatarImageProcessor;
    private final AvatarStorageService avatarStorageService;

    public AvatarService(
            UserRepository userRepository,
            AvatarImageProcessor avatarImageProcessor,
            AvatarStorageService avatarStorageService) {
        this.userRepository = userRepository;
        this.avatarImageProcessor = avatarImageProcessor;
        this.avatarStorageService = avatarStorageService;
    }

    @Transactional
    public UserProfileResponse uploadAvatar(Long userId, String contentType, byte[] rawBytes) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new UnsupportedAvatarFormatException("対応していない画像形式です: " + contentType);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id " + userId + " のユーザーは登録されていません"));

        byte[] processed = avatarImageProcessor.process(rawBytes, contentType);
        avatarStorageService.store(userId, processed);

        user.setAvatarUrl("/api/users/" + userId + "/avatar");
        return UserProfileResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public byte[] loadAvatar(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("id " + userId + " のユーザーは登録されていません");
        }
        return avatarStorageService.load(userId)
                .orElseThrow(() -> new AvatarNotFoundException("id " + userId + " のアバターは未設定です"));
    }
}
