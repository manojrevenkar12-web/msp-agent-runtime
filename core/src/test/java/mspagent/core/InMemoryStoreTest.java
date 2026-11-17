package mspagent.core;

import mspagent.core.engine.InMemoryRuntimeStore;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.engine.RuntimeStoreContract;

class InMemoryStoreTest extends RuntimeStoreContract {
    private final RuntimeStore store = new InMemoryRuntimeStore();

    @Override
    protected RuntimeStore store() {
        return store;
    }
}
