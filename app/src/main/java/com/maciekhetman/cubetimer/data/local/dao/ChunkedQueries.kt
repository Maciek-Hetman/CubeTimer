package com.maciekhetman.cubetimer.data.local.dao

import com.maciekhetman.cubetimer.data.local.entity.SolveEntity

/**
 * Max ids bound into a single `IN (...)` list. SQLite before 3.32 (Android 11 / API 30 and below)
 * rejects statements with more than 999 bind variables, and Room doesn't split list parameters,
 * so bulk operations over a user's whole history would fail there with "too many SQL variables".
 */
const val MAX_IN_LIST_SIZE = 500

/**
 * [SolveDao.getSolvesByIds] split into batches that stay under SQLite's bind-variable limit.
 * Extension functions (not DAO default methods) so they dispatch through the receiver's own
 * overrides, which keeps test decorators built with `SolveDao by delegate` working.
 */
suspend fun SolveDao.getSolvesByIdsChunked(ids: List<String>): List<SolveEntity> =
    ids.chunked(MAX_IN_LIST_SIZE).flatMap { getSolvesByIds(it) }

/**
 * [SolveDao.softDeleteAll] split into batches that stay under SQLite's bind-variable limit.
 * Not atomic on its own: callers run it inside a Room transaction.
 */
suspend fun SolveDao.softDeleteAllChunked(ids: List<String>, deletedAt: String, updatedAt: String): Int =
    ids.chunked(MAX_IN_LIST_SIZE).sumOf { softDeleteAll(it, deletedAt, updatedAt) }
