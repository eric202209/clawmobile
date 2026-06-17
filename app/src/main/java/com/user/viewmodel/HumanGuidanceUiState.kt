package com.user.viewmodel

import com.user.data.HumanGuidanceConflict
import com.user.data.HumanGuidanceEntry
import com.user.data.HumanGuidanceReadiness
import com.user.data.HumanGuidanceRendered

sealed class HumanGuidanceUiState {
    object Loading : HumanGuidanceUiState()
    data class Ready(
        val readiness: HumanGuidanceReadiness,
        val guidance: List<HumanGuidanceEntry>,
        val conflicts: List<HumanGuidanceConflict>,
        val isAdvancedMode: Boolean = false,
        val rendered: HumanGuidanceRendered? = null,
    ) : HumanGuidanceUiState()
    data class Error(val message: String) : HumanGuidanceUiState()
}
