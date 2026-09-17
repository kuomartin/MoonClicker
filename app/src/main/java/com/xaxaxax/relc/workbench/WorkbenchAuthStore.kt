package com.xaxaxax.relc.workbench

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

sealed interface PairResult {
    data class Success(val token: String) : PairResult
    data object PairingModeInactive : PairResult
    data object InvalidPin : PairResult
    data class LockedOut(val retryAfterSeconds: Int) : PairResult
}

@Singleton
class WorkbenchAuthStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("workbench_auth", Context.MODE_PRIVATE)

    private val _isPairingActive = MutableStateFlow(false)
    val isPairingActive: StateFlow<Boolean> = _isPairingActive.asStateFlow()

    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    private val _pairingExpiryMs = MutableStateFlow(0L)
    val pairingExpiryMs: StateFlow<Long> = _pairingExpiryMs.asStateFlow()

    private val _bruteForceProtectionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_BRUTE_FORCE_PROTECTION, true)
    )
    val bruteForceProtectionEnabled: StateFlow<Boolean> = _bruteForceProtectionEnabled.asStateFlow()

    private val _authorizedTokens = MutableStateFlow(loadTokens())
    val authorizedTokens: StateFlow<Set<String>> = _authorizedTokens.asStateFlow()

    private var failedAttempts = 0
    private var lockoutUntilMs = 0L

    fun startPairingMode(): String {
        val pin = String.format(Locale.US, "%06d", Random.nextInt(1000000))
        val expiry = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
        _pairingPin.value = pin
        _pairingExpiryMs.value = expiry
        _isPairingActive.value = true
        failedAttempts = 0
        return pin
    }

    fun stopPairingMode() {
        _isPairingActive.value = false
        _pairingPin.value = null
        _pairingExpiryMs.value = 0L
    }

    fun checkAndExpirePairingMode(): Boolean {
        if (_isPairingActive.value && System.currentTimeMillis() > _pairingExpiryMs.value) {
            stopPairingMode()
        }
        return _isPairingActive.value
    }

    fun pairWithPin(pin: String): PairResult {
        val now = System.currentTimeMillis()
        if (_bruteForceProtectionEnabled.value && now < lockoutUntilMs) {
            val retryAfterSec = ((lockoutUntilMs - now) / 1000).toInt().coerceAtLeast(1)
            return PairResult.LockedOut(retryAfterSec)
        }

        if (!checkAndExpirePairingMode()) {
            return PairResult.PairingModeInactive
        }

        if (pin != _pairingPin.value) {
            if (_bruteForceProtectionEnabled.value) {
                failedAttempts++
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    lockoutUntilMs = now + LOCKOUT_DURATION_MS
                    stopPairingMode()
                    return PairResult.LockedOut((LOCKOUT_DURATION_MS / 1000).toInt())
                }
            }
            return PairResult.InvalidPin
        }

        // Correct PIN
        val newToken = UUID.randomUUID().toString()
        addToken(newToken)
        stopPairingMode()
        return PairResult.Success(newToken)
    }

    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return _authorizedTokens.value.contains(token)
    }

    fun setBruteForceProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRUTE_FORCE_PROTECTION, enabled).apply()
        _bruteForceProtectionEnabled.value = enabled
    }

    fun revokeToken(token: String) {
        val updated = _authorizedTokens.value.toMutableSet()
        updated.remove(token)
        saveTokens(updated)
    }

    fun revokeAllTokens() {
        saveTokens(emptySet())
    }

    private fun addToken(token: String) {
        val updated = _authorizedTokens.value.toMutableSet()
        updated.add(token)
        saveTokens(updated)
    }

    private fun loadTokens(): Set<String> {
        return prefs.getStringSet(KEY_AUTHORIZED_TOKENS, null) ?: emptySet()
    }

    private fun saveTokens(tokens: Set<String>) {
        prefs.edit().putStringSet(KEY_AUTHORIZED_TOKENS, tokens).apply()
        _authorizedTokens.value = tokens
    }

    companion object {
        private const val KEY_BRUTE_FORCE_PROTECTION = "brute_force_protection"
        private const val KEY_AUTHORIZED_TOKENS = "authorized_tokens"
        private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val LOCKOUT_DURATION_MS = 60 * 1000L // 60 seconds
    }
}
