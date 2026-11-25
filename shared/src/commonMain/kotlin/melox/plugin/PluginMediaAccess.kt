package melox.plugin

/** Opaque host authorization snapshot; never serialized or passed to native decoders. */
class PluginMediaAccess internal constructor(
    internal val current: () -> Boolean,
    private val create: (() -> Boolean) -> PluginHttpBroker,
) {
    internal fun broker(active: () -> Boolean): PluginHttpBroker {
        check(current() && active()) { "AUTH_EXPIRED" }
        return create { current() && active() }
    }
}
