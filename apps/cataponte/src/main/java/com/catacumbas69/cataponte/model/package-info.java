/**
 * MongoDB documents owned by Cataponte (ARCH-1, ADR 0010), all in {@code cataponte_*} collections. The
 * {@code cataponte_bridges} collection is the desired state that the reconciler applies (ARCH-4);
 * {@code cataponte_worker_status} is what each replica reports about its workers.
 */
package com.catacumbas69.cataponte.model;
