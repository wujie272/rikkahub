package me.rerere.rikkahub.browser

/**
 * Outbound hook the [BrowserController] uses to push a screenshot + caption into
 * the calling chat after every state-changing tool in headless mode.
 *
 * Decoupling this behind an interface lets the controller dispatch into
 * a transport-specific implementation registered via Koin. The same interface can be reused
 * by cron / sub-agent surfaces that want their own screenshot stream.
 *
 * Implementations:
 *  - [NoOp]: no-op default — used in JVM unit tests and any context where Koin hasn't
 *    registered a real streamer.
 */
interface BrowserScreenshotStreamer {

    /**
     * Send [screenshotPath] (an absolute file path to a PNG on disk) into the chat
     * identified by [callerConvId] with a one-line caption derived from [actionLabel] and
     * the destination [currentUrl]. Best-effort — implementations log failures and return.
     *
     * Implementations should NOT delete the file after sending; the [BrowserController]
     * writes screenshots to a cache subdir that is swept by the OS (and the user's manual
     * "Clear cache" Doctor row).
     */
    suspend fun send(
        callerConvId: String,
        screenshotPath: String,
        actionLabel: String,
        currentUrl: String?,
    )

    /**
     * Default implementation used when no real streamer is registered (JVM tests, or a
     * device build that has no streamer registered). Eats the call without ceremony.
     */
    object NoOp : BrowserScreenshotStreamer {
        override suspend fun send(
            callerConvId: String,
            screenshotPath: String,
            actionLabel: String,
            currentUrl: String?,
        ) {
            // intentional no-op
        }
    }
}
