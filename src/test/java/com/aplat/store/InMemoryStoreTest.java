package com.aplat.store;

import com.aplat.seam.Store;

/**
 * 内存实现跑契约。它的角色是**参照物**：证明这套契约是可满足的，
 * 从而让 JDBC 实现的失败是"实现的问题"而不是"契约本身自相矛盾"。
 */
class InMemoryStoreTest extends StoreContract {

    private final InMemoryStore store = new InMemoryStore();

    @Override
    protected Store store() {
        return store;
    }

    @Override
    protected void reset() {
        store.clear();
    }
}
