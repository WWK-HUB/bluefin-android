package com.wwkhub.bluefin

import android.content.Context

/**
 * Maps an import failure onto the sentence the user sees.
 *
 * Shared by the zip importer and the in-app creator: both install through
 * [VoicePackRepository.install], so both can surface the same reasons. Keeping
 * it a free function means neither activity owns the wording.
 */
internal fun voicePackProblem(context: Context, error: VoicePackException): String =
    context.getString(
        when (error.reason) {
            VoicePackReject.DELETION_PENDING -> R.string.speech_voice_delete_pending_reimport
            VoicePackReject.BUILT_IN_ID,
            VoicePackReject.RESERVED_ID,
            -> R.string.speech_voice_reject_reserved
            VoicePackReject.INVALID_ID -> R.string.speech_voice_reject_invalid_id
            VoicePackReject.INCOMPATIBLE -> R.string.speech_voice_reject_incompatible
            VoicePackReject.INVALID_REFERENCE -> R.string.speech_voice_reject_reference
            VoicePackReject.INCOMPLETE -> R.string.speech_voice_reject_incomplete
            VoicePackReject.INVALID_TEXT,
            VoicePackReject.INVALID_TUNING,
            VoicePackReject.BAD_JSON,
            -> R.string.speech_voice_reject_generic
        },
    )
