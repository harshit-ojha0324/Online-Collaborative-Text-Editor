package org.cce.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.util.Base64;

@SpringBootTest
class BackendApplicationTests {

    // Generate a throwaway signing key at runtime so the context loads without the real
    // JWT_SECRET_KEY env var — and without committing any secret-shaped literal to the repo.
    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret-key", () -> secret);
    }

    @Test
    void contextLoads() {
    }

}
