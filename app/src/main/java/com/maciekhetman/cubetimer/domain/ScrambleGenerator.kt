package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.worldcubeassociation.tnoodle.scrambles.PuzzleRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates WCA-style scrambles via TNoodle.
 *
 * TNoodle's puzzle searchers are ThreadLocal and expensive to build (3x3 has a hard 200ms minimum
 * search plus JIT warm-up cost; 4x4's first search can take on the order of a second, or much
 * longer while still running interpreted). To keep this fast in practice:
 *  - generation is confined to a single dedicated background thread so the ThreadLocal searchers
 *    are built once and reused across calls instead of a fresh one per Dispatchers.Default thread.
 *  - a per-mode one-slot cache holds a pre-generated "next" scramble; [nextScramble] takes it
 *    instantly when present and refills the cache in the background for the following call.
 */
object ScrambleGenerator {
    private val scramblersByMode = mapOf(
        Mode.CUBE_2x2 to PuzzleRegistry.TWO,
        Mode.CUBE_3x3 to PuzzleRegistry.THREE,
        Mode.CUBE_4x4 to PuzzleRegistry.FOUR,
        Mode.CUBE_5x5 to PuzzleRegistry.FIVE,
        Mode.MEGAMINX to PuzzleRegistry.MEGA,
        Mode.PYRAMINX to PuzzleRegistry.PYRA
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scrambleDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scrambleScope = CoroutineScope(SupervisorJob() + scrambleDispatcher)

    private val nextScrambleCache = ConcurrentHashMap<Mode, String>()

    /**
     * Synchronous scramble generation, for callers that can't suspend (including existing tests).
     * Does not consult or populate the pre-generated cache, and blocks the calling thread for the
     * full cost of the puzzle's search.
     */
    fun generateScramble(mode: Mode): String {
        AndroidSha1PrngProvider.install()
        return generateScrambleBlocking(mode)
    }

    /**
     * Ensures a pre-generated scramble is cached for [mode] without consuming it. Idempotent: a
     * no-op if a scramble is already cached.
     */
    suspend fun warmUp(mode: Mode) {
        AndroidSha1PrngProvider.install()
        if (nextScrambleCache.containsKey(mode)) return
        withContext(scrambleDispatcher) {
            if (!nextScrambleCache.containsKey(mode)) {
                nextScrambleCache[mode] = generateScrambleBlocking(mode)
            }
        }
    }

    /**
     * Returns the next scramble for [mode]: a pre-generated one from the cache when available
     * (effectively instant), otherwise generated on the dedicated scramble thread. Either way, a
     * background refill for the following call is kicked off before returning.
     */
    suspend fun nextScramble(mode: Mode): String {
        AndroidSha1PrngProvider.install()
        val cached = nextScrambleCache.remove(mode)
        val scramble = cached ?: withContext(scrambleDispatcher) { generateScrambleBlocking(mode) }
        refillInBackground(mode)
        return scramble
    }

    private fun refillInBackground(mode: Mode) {
        scrambleScope.launch {
            if (!nextScrambleCache.containsKey(mode)) {
                nextScrambleCache[mode] = generateScrambleBlocking(mode)
            }
        }
    }

    private fun generateScrambleBlocking(mode: Mode): String {
        val registry = scramblersByMode.getValue(mode)
        return registry.getScrambler().generateScramble()
    }
}
