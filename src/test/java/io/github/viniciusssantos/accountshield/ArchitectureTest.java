package io.github.viniciusssantos.accountshield;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    private final ApplicationModules modules = ApplicationModules.of(AccountShieldApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }
}
