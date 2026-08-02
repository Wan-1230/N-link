package com.nikonlink.app.di

import android.content.Context
import androidx.room.Room
import com.nikonlink.app.core.ble.BleManager
import com.nikonlink.app.core.connection.ConnectionManager
import com.nikonlink.app.core.connection.ConnectionStateMachine
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.ptp.PtpIdentityStore
import com.nikonlink.app.core.usb.UsbPtpManager
import com.nikonlink.app.core.wifi.WifiManager
import com.nikonlink.app.core.wifi.WifiScanner
import com.nikonlink.app.data.local.NikonLinkDatabase
import com.nikonlink.app.data.local.PairedDeviceDao
import com.nikonlink.app.data.local.TransferHistoryDao
import com.nikonlink.app.data.repository.DeviceRepository
import com.nikonlink.app.data.repository.TransferRepository
import com.nikonlink.app.feature.liveview.LiveViewManager
import com.nikonlink.app.feature.remote.RemoteShootingManager
import com.nikonlink.app.feature.settings.CameraParameterManager
import com.nikonlink.app.feature.transfer.TransferManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 * PRD 4.2: DI 框架 - Hilt（官方推荐，编译期验证）
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context
    ): BleManager = BleManager(context)

    @Provides
    @Singleton
    fun provideWifiManager(
        @ApplicationContext context: Context
    ): WifiManager = WifiManager(context)

    @Provides
    @Singleton
    fun provideWifiScanner(
        @ApplicationContext context: Context
    ): WifiScanner = WifiScanner(context)

    @Provides
    @Singleton
    fun providePtpSessionManager(
        identityStore: PtpIdentityStore
    ): PtpSessionManager = PtpSessionManager(identityStore)

    @Provides
    @Singleton
    fun provideConnectionStateMachine(): ConnectionStateMachine = ConnectionStateMachine()

    @Provides
    @Singleton
    fun provideConnectionManager(
        bleManager: BleManager,
        wifiManager: WifiManager,
        wifiScanner: WifiScanner,
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager,
        stateMachine: ConnectionStateMachine,
        deviceRepository: DeviceRepository
    ): ConnectionManager = ConnectionManager(
        bleManager = bleManager,
        wifiManager = wifiManager,
        wifiScanner = wifiScanner,
        ptpSession = ptpSessionManager,
        usbPtpManager = usbPtpManager,
        stateMachine = stateMachine,
        deviceRepository = deviceRepository
    )

    @Provides
    @Singleton
    fun provideRemoteShootingManager(
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager
    ): RemoteShootingManager = RemoteShootingManager(ptpSessionManager, usbPtpManager)

    @Provides
    @Singleton
    fun provideCameraParameterManager(
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager
    ): CameraParameterManager = CameraParameterManager(ptpSessionManager, usbPtpManager)

    @Provides
    @Singleton
    fun provideTransferManager(
        @ApplicationContext context: Context,
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager,
        transferRepository: TransferRepository
    ): TransferManager = TransferManager(
        context,
        ptpSessionManager,
        usbPtpManager,
        transferRepository
    )

    @Provides
    @Singleton
    fun provideLiveViewManager(
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager
    ): LiveViewManager = LiveViewManager(ptpSessionManager, usbPtpManager)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): NikonLinkDatabase = Room.databaseBuilder(
        context,
        NikonLinkDatabase::class.java,
        "nikonlink.db"
    ).build()

    @Provides
    @Singleton
    fun provideTransferHistoryDao(db: NikonLinkDatabase): TransferHistoryDao = db.transferHistoryDao()

    @Provides
    @Singleton
    fun providePairedDeviceDao(db: NikonLinkDatabase): PairedDeviceDao = db.pairedDeviceDao()

    @Provides
    @Singleton
    fun provideUsbPtpManager(
        @ApplicationContext context: Context
    ): UsbPtpManager = UsbPtpManager(context)
}
