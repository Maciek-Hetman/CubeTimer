package com.maciekhetman.cubetimer.testutil

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind

/**
 * Seeds an open session through the repository (so outbox mutations are enqueued exactly as for
 * a real write). [kind] defaults to MANUAL - the kind other clients or older builds may sync down -
 * because such sessions are inert to the automatic-session policy and never become the active
 * session. Pass [SessionKind.AUTOMATIC] to seed the open automatic session instead.
 */
suspend fun SessionRepository.insertSession(
    name: String,
    mode: Mode,
    ownerId: String = "guest",
    kind: SessionKind = SessionKind.MANUAL
): Session = createSession(
    Session(
        ownerId = ownerId,
        name = name.trim().ifBlank { "Session" },
        event = mode,
        kind = kind,
        startedAt = CubeTypeConverters.nowIso()
    )
)
