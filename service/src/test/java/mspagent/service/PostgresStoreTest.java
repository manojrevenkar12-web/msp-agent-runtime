package mspagent.service;

import mspagent.core.engine.RuntimeStore;
import mspagent.core.engine.RuntimeStoreContract;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/** The PostgreSQL store keeps the same contract as the in-memory one. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
class PostgresStoreTest extends ServiceTest {
    @Autowired
    RuntimeStore store;

    @Nested
    class Contract extends RuntimeStoreContract {
        @Override
        protected RuntimeStore store() {
            return store;
        }
    }
}
