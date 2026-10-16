package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** {@link UserMigrationPersister}: 1ユーザー分の保存を、独立したトランザクション(REQUIRES_NEW)で行う(issue #964)。 */
class UserMigrationPersisterTest {

    @Test
    void saveAndFlushはリポジトリのsaveAndFlushへそのまま渡す() {
        UserRepository userRepository = mock(UserRepository.class);
        User user = new User();
        user.setId(1L);

        new UserMigrationPersister(userRepository).saveAndFlush(user);

        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void saveAndFlushは呼び出し元と独立した新規トランザクションで実行される() throws NoSuchMethodException {
        // 実際のHibernateセッション分離は、トランザクション属性(REQUIRES_NEW)がSpringのプロキシで
        // 効くことに依る。ここではその宣言が外れていないことを固定する。
        Transactional transactional =
                UserMigrationPersister.class.getMethod("saveAndFlush", User.class).getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }
}
