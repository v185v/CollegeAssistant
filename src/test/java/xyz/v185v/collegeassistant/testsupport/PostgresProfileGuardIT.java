package xyz.v185v.collegeassistant.testsupport;

import org.junit.jupiter.api.Test;

class PostgresProfileGuardIT {

    @Test
    void requiredIntegrationEnvironmentIsPresent() {
        DatabaseTestSupport.requireIntegrationEnvironment();
    }
}
