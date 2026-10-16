package com.letsblog.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複合主キー({@link ProjectUserId}・{@link UserSiteAuthorId})の同一性。JPAは複合主キーを永続化コンテキストの
 * キーとして{@code equals}/{@code hashCode}で比べるため、各フィールドを区別することを固定する。
 */
class CompositeIdTest {

    @Test
    void ProjectUserId_同じ値なら等しくハッシュも一致する() {
        ProjectUserId a = new ProjectUserId(1L, 2L);
        ProjectUserId b = new ProjectUserId(1L, 2L);

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<Object> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertEquals(1, set.size());
    }

    @Test
    void ProjectUserId_どちらかの値が違えば等しくない() {
        ProjectUserId base = new ProjectUserId(1L, 2L);

        assertNotEquals(base, new ProjectUserId(9L, 2L));
        assertNotEquals(base, new ProjectUserId(1L, 9L));
        assertNotEquals(base, new ProjectUserId());
    }

    @Test
    void ProjectUserId_別の型やnullとは等しくない() {
        ProjectUserId id = new ProjectUserId(1L, 2L);

        assertFalse(id.equals(null));
        assertFalse(id.equals("1-2"));
        assertTrue(new ProjectUserId().equals(new ProjectUserId()));
    }

    @Test
    void UserSiteAuthorId_同じ値なら等しくハッシュも一致する() {
        UserSiteAuthorId a = new UserSiteAuthorId(1L, 2L);
        UserSiteAuthorId b = new UserSiteAuthorId(1L, 2L);

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<Object> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertEquals(1, set.size());
    }

    @Test
    void UserSiteAuthorId_どちらかの値が違えば等しくない() {
        UserSiteAuthorId base = new UserSiteAuthorId(1L, 2L);

        assertNotEquals(base, new UserSiteAuthorId(9L, 2L));
        assertNotEquals(base, new UserSiteAuthorId(1L, 9L));
        assertNotEquals(base, new UserSiteAuthorId());
    }

    @Test
    void UserSiteAuthorId_別の型やnullとは等しくない() {
        UserSiteAuthorId id = new UserSiteAuthorId(1L, 2L);

        assertFalse(id.equals(null));
        assertFalse(id.equals("1-2"));
        assertTrue(new UserSiteAuthorId().equals(new UserSiteAuthorId()));
    }
}
