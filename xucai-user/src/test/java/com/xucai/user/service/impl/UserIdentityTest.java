package com.xucai.user.service.impl;

import com.xucai.common.exceptions.UnauthorizedException;
import com.xucai.common.utils.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UserIdentityTest {
    @Test
    void currentUserRequiresTrustedIdentityBeforeReadingDatabase() {
        UserContext.removeUser();
        UserServiceImpl service = Mockito.mock(UserServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        assertThrows(UnauthorizedException.class, service::myInfo);
    }
}
