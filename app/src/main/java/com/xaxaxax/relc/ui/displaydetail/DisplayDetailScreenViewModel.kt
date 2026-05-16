package com.xaxaxax.relc.ui.displaydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject


@HiltViewModel
class DisplayDetailScreenViewModel
@Inject constructor() : ViewModel() {

    fun closeDisplay(displayId: Int) {
        viewModelScope.launch {
            val serviceFlow = UserService.create(
                this,
                RelcV2Service::class,
                IRelcV2Service.Stub::asInterface
            )
            serviceFlow.runWhenAlive { service ->
                service.destroyVirtualDisplay(displayId)
            }.onSuccess {
                Timber.d("Destroy VirtualDisplay #$displayId")
            }.onFailure {
                Timber.e(it, "Failed to update displayList.")
            }
        }
    }
}