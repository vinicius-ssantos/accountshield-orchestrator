package io.github.viniciusssantos.accountshield;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class AccountShieldApplicationTest {

    @Test
    void contextLoads() {
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PostgreSqlTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("accountshield")
                .withUsername("accountshield")
                .withPassword("accountshield-test-only");
    }
}
