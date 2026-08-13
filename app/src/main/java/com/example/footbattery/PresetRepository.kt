package com.example.footbattery

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

enum class FootwearPreset(val displayName: String, val summaryName: String) {
    BAREFOOT("Barefoot", "Barefoot"),
    RUNNING("Running", "Running shoes"),
    DRESS("Dress", "Dress shoes"),
    BOOTS("Boots", "Boots");

    companion object {
        val fixedOrder: List<FootwearPreset> = listOf(BAREFOOT, RUNNING, DRESS, BOOTS)
    }
}
data class PresetTargets(
    val barefootMd: Int? = null,
    val runningMd: Int? = null,
    val dressMd: Int? = null,
    val bootsMd: Int? = null
) {
    fun target(preset: FootwearPreset): Int? = when (preset) {
        FootwearPreset.BAREFOOT -> barefootMd
        FootwearPreset.RUNNING -> runningMd
        FootwearPreset.DRESS -> dressMd
        FootwearPreset.BOOTS -> bootsMd
    }?.takeIf(AnkleProtocol::isSupported)

    fun save(preset: FootwearPreset, confirmedMd: Int): PresetTargets {
        require(AnkleProtocol.isSupported(confirmedMd))
        return when (preset) {
            FootwearPreset.BAREFOOT -> copy(barefootMd = confirmedMd)
            FootwearPreset.RUNNING -> copy(runningMd = confirmedMd)
            FootwearPreset.DRESS -> copy(dressMd = confirmedMd)
            FootwearPreset.BOOTS -> copy(bootsMd = confirmedMd)
        }
    }

    fun activeMatches(confirmedMd: Int?): List<FootwearPreset> =
        confirmedMd?.let { md -> FootwearPreset.fixedOrder.filter { target(it) == md } }.orEmpty()
}

data class PresetState(
    val targets: PresetTargets = PresetTargets(),
    val selected: FootwearPreset? = null
)

fun summaryPreset(state: PresetState, confirmedMd: Int?): FootwearPreset? {
    val matches = state.targets.activeMatches(confirmedMd)
    return state.selected?.takeIf { it in matches } ?: matches.firstOrNull()
}

/** Four immutable slots; only their exact confirmed millidegree targets are persisted. */
object PresetRepository {
    private val initialized = AtomicBoolean(false)
    val state = MutableStateFlow(PresetState())

    fun ensureInitialized(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        state.value = PresetState(targets = Prefs.presetTargets(ctx.applicationContext))
    }

    fun select(preset: FootwearPreset) {
        state.value = state.value.copy(selected = preset)
    }

    fun saveSelected(ctx: Context, confirmedMd: Int): FootwearPreset? {
        val selected = state.value.selected ?: return null
        require(AnkleProtocol.isSupported(confirmedMd))
        val targets = state.value.targets.save(selected, confirmedMd)
        Prefs.savePresetTarget(ctx.applicationContext, selected, confirmedMd)
        state.value = state.value.copy(targets = targets)
        return selected
    }
}
