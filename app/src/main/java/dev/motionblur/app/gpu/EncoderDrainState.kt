package dev.motionblur.app.gpu

/** Pure lifecycle guard for a Surface-input MediaCodec encoder. */
internal class EncoderDrainState {
    private var inputEosRequested = false
    private var codecEosReceived = false

    /** Returns true only for the one legal signalEndOfInputStream call. */
    fun requestInputEos(): Boolean {
        if (inputEosRequested) return false
        inputEosRequested = true
        return true
    }

    /** Accepts one codec EOS only after input EOS has been requested. */
    fun acceptCodecEos(): Boolean {
        if (!inputEosRequested || codecEosReceived) return false
        codecEosReceived = true
        return true
    }
}
