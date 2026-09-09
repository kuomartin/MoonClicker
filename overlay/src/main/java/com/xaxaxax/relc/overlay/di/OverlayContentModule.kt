package com.xaxaxax.relc.overlay.di

import com.xaxaxax.relc.overlay.LuaOverlayContentExtension
import com.xaxaxax.relc.overlay.OverlayContentExtension
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class OverlayContentModule {
    @Binds
    @IntoMap
    @StringKey("LUA")
    abstract fun bindLuaOverlayContentExtension(
        impl: LuaOverlayContentExtension
    ): OverlayContentExtension
}
