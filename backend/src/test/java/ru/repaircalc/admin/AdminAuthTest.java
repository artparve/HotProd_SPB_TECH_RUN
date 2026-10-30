package ru.repaircalc.admin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthTest {
    private static final String HASH = "pbkdf2-sha256$600000$cmVwYWlyY2FsYy10ZXN0LXNhbHQtMjAyNg==$gjyWxpevd0JL54Z/gOniKJcBX3zkrSyCsUk8Bhd5uqY=";

    @Test
    void disabledByDefaultAndRejectsWeakHashConfiguration() {
        assertThat(new AdminAuth("", "", 1800).enabled()).isFalse();
        assertThatThrownBy(() -> new AdminAuth("admin", HASH.replace("600000", "1000"), 1800)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absoluteExpirationInvalidatesRoleAndCsrfEvenIfSessionWasUsed() {
        var auth = new AdminAuth("admin", HASH, 60);
        var request = new MockHttpServletRequest();
        var session = request.getSession(true);
        session.setAttribute(AdminAuth.ACTOR, "admin");
        session.setAttribute(AdminAuth.CSRF, "old-token");
        session.setAttribute("repaircalc.admin.issued", java.time.Instant.now().getEpochSecond() - 61);
        assertThat(auth.actor(request)).isNull();
        var refreshed = auth.session(request);
        assertThat(refreshed.authenticated()).isFalse();
        assertThat(refreshed.csrfToken()).isNotEqualTo("old-token");
    }
}
