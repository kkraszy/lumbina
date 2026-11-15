package dev.lumbina.tv.platform.adb

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumbina.tv.core.domain.access.LocalAdb
import dev.lumbina.tv.core.domain.device.AttachedDevices

@Module
@InstallIn(SingletonComponent::class)
internal interface AdbModule {
    @Binds fun localAdb(impl: AndroidLocalAdb): LocalAdb

    @Binds fun attachedDevices(impl: UsbAttachedDevices): AttachedDevices
}
