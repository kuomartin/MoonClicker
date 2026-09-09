package com.xaxaxax.relc.di

import com.xaxaxax.relc.overlay.OverlayContentExtension
import com.xaxaxax.relc.overlay.ui.simple.SimpleOverlayContentExtension
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
    @StringKey("SIMPLE")
    abstract fun bindSimpleOverlayContentExtension(
        impl: SimpleOverlayContentExtension
    ): OverlayContentExtension
}
