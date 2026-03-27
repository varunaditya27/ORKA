package com.orka.data.rl

import com.orka.core.model.RlTrainer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RlBindingsModule {
    @Binds
    abstract fun bindRlTrainer(impl: DefaultRlTrainer): RlTrainer
}
