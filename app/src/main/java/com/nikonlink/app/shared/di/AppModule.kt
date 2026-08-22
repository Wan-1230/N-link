package com.nikonlink.app.shared.di

import android.content.Context
import androidx.room.Room
import com.nikonlink.app.device.ble.BleManager
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.connect.ConnectionStateMachine
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.ptp.PtpIdentityStore
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.device.wifi_sta.WifiScanner
import com.nikonlink.app.shared.data.NLinkDatabase
import com.nikonlink.app.shared.data.PairedDeviceDao
import com.nikonlink.app.shared.data.TransferHistoryDao
import com.nikonlink.app.device.data.DeviceRepository
import com.nikonlink.app.camera.data.TransferRepository
import com.nikonlink.app.camera.liveview.LiveViewManager
import com.nikonlink.app.capture.RemoteShootingManager
import com.nikonlink.app.camera.params.CameraParameterManager
import com.nikonlink.app.camera.params.DigeekerShutterCountClient
import com.nikonlink.app.camera.gallery.TransferManager
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
        @ApplicationContext context: Context,
        ptpSessionManager: PtpSessionManager,
        usbPtpManager: UsbPtpManager,
        transferManager: TransferManager,
        digeekerShutterCountClient: DigeekerShutterCountClient
    ): CameraParameterManager = CameraParameterManager(
        context,
        ptpSessionManager,
        usbPtpManager,
        transferManager,
        digeekerShutterCountClient
    )

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
    ): NLinkDatabase = Room.databaseBuilder(
        context,
        NLinkDatabase::class.java,
        "n-link.db"
    ).build()

    @Provides
    @Singleton
    fun provideTransferHistoryDao(db: NLinkDatabase): TransferHistoryDao = db.transferHistoryDao()

    @Provides
    @Singleton
    fun providePairedDeviceDao(db: NLinkDatabase): PairedDeviceDao = db.pairedDeviceDao()

    @Provides
    @Singleton
    fun provideUsbPtpManager(
        @ApplicationContext context: Context
    ): UsbPtpManager = UsbPtpManager(context)
}
