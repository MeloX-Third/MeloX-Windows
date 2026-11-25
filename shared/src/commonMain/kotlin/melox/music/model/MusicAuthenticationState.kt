package melox.music.model

sealed interface MusicAuthenticationState {
    data object NotRequired : MusicAuthenticationState
    data object SignedOut : MusicAuthenticationState
    data class SignedIn(val account: MusicAccountSummary) : MusicAuthenticationState
    data class Expired(val accountId: String?) : MusicAuthenticationState
}
