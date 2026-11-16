package com.fileapex.domain.link

/**
 * What a health poll tells its peer about the Simple Home link. A computer sends [Kind.Companion] to a phone,
 * a phone sends [Kind.Link] to a computer. A guess carries only the flag, which is what older builds understand.
 */
data class HealthSignal(val kind: Kind, val signal: PairLink.Signal) {
    enum class Kind(val param: String) { Companion("companion"), Link("link") }

    fun query(): String {
        val flag = "${kind.param}=${PairLink.activeValue(signal)}"
        val at = PairLink.chosenAtValue(signal)
        return if (at > 0L) "$flag&$CHOSEN_AT=$at" else flag
    }

    companion object {
        const val CHOSEN_AT = "chosenAt"
    }
}
