package io.github.viniciusssantos.accountshield;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class AccountShieldApplicationTest {

    @Test
    void contextLoads() {
    }
}
