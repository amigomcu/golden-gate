// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.linkcontroller.services.configuration

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice
import com.fitbit.bluetooth.fbgatt.GattServerConnection
import com.fitbit.bluetooth.fbgatt.TransactionResult
import com.fitbit.bluetooth.fbgatt.rx.CLIENT_CONFIG_UUID
import com.fitbit.bluetooth.fbgatt.rx.server.GattServerResponseSenderProvider
import com.fitbit.bluetooth.fbgatt.rx.server.listeners.BaseServerConnectionEventListener
import com.fitbit.linkcontroller.LinkControllerProvider
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import timber.log.Timber

/**
 * Duplicating [BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE] and [BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE]
 * values as a work around for exception thrown when accessing these value directly from unit test
 */
internal val gattServiceSubscribedValue = byteArrayOf(0x01, 0x00)
internal val gattServiceUnSubscribedValue = byteArrayOf(0x00, 0x00)

/**
 * GATT service listener that listens to subscription changes to Link Configuration service.
 * Subscription changes are available per remote connected device
 */
class LinkConfigurationServiceEventListener internal constructor(
    private val responseScheduler: Scheduler = Schedulers.io(),
    private val gattServerResponseSenderProvider: GattServerResponseSenderProvider = GattServerResponseSenderProvider()
) : BaseServerConnectionEventListener {

    lateinit var linkControllerProvider:LinkControllerProvider
    private val registry =
        hashMapOf<BluetoothDevice, Subject<GattCharacteristicSubscriptionStatus>>()

    /**
     * Observable on which changes to Gattlink service subscription is available
     *
     * @param device remote device for which subscription status change need to be observer
     * @return observable to broadcast Gattlink service subscription changes per device
     */
    fun getDataObservable(device: BluetoothDevice): Observable<GattCharacteristicSubscriptionStatus> =
        getDataSubject(device)

    @Synchronized
    private fun getDataSubject(device: BluetoothDevice): Subject<GattCharacteristicSubscriptionStatus> {
        return registry[device] ?: add(device)
    }

    @Synchronized
    private fun add(device: BluetoothDevice): Subject<GattCharacteristicSubscriptionStatus> {
        val dataSubject = BehaviorSubject.createDefault<GattCharacteristicSubscriptionStatus>(GattCharacteristicSubscriptionStatus.DISABLED)
        registry[device] = dataSubject
        return dataSubject
    }

    override fun onServerDescriptorWriteRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        when (result.serviceUuid) {
            LinkConfigurationService.uuid -> handleLinkConfigurationServerDescriptorWriteRequest(
                device,
                result,
                connection
            )
            else -> Timber.d("Ignoring onServerDescriptorWriteRequest call for unsupported service: ${result.serviceUuid}")
        }
    }

    private fun handleLinkConfigurationServerDescriptorWriteRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        Timber.d(
            """
            Handle handleLinkConfigurationServerDescriptorWriteRequest call from
            device ${device.address},
            service: ${result.serviceUuid},
            characteristicUuid: ${result.characteristicUuid},
            descriptorUuid: ${result.descriptorUuid}
            """
        )
        when (result.characteristicUuid) {
            ClientPreferredConnectionModeCharacteristic.uuid,
            ClientPreferredConnectionConfigurationCharacteristic.uuid,
            GeneralPurposeCommandCharacteristic.uuid-> handleLinkConfigurationCharacteristicDescriptorWriteRequest(
                device,
                result,
                connection
            )
            else -> {
                Timber.d("Ignoring onServerDescriptorWriteRequest call for unsupported characteristicUuid: ${result.characteristicUuid}")
                sendFailureResponseIfRequested(device, result, connection)
            }
        }
    }

    private fun handleLinkConfigurationCharacteristicDescriptorWriteRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        when (result.descriptorUuid) {
            CLIENT_CONFIG_UUID -> handleConfigurationDescriptorWriteRequest(
                device,
                result,
                connection
            )
            else -> {
                Timber.d("Ignoring onServerDescriptorWriteRequest call for unsupported descriptor: ${result.descriptorUuid}")
                sendFailureResponseIfRequested(device, result, connection)
            }
        }
    }

    private fun handleConfigurationDescriptorWriteRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        result.data?.let { data ->
            when {
                data.contentEquals(gattServiceSubscribedValue) -> handleTransmitCharacteristicSubscriptionEnabled(
                    device,
                    result,
                    connection
                )
                data.contentEquals(gattServiceUnSubscribedValue) -> handleTransmitCharacteristicSubscriptionDisabled(
                    device,
                    result,
                    connection
                )
                else -> {
                    Timber.w("Ignoring descriptor write request ")
                    sendFailureResponseIfRequested(device, result, connection)
                }
            }
        } ?: handlerNullDataReceived(device, result, connection)
    }

    private fun handleTransmitCharacteristicSubscriptionEnabled(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        Timber.d("Device: $device SUBSCRIBED to LinkConfiguration service ")
        getDataSubject(device).onNext(GattCharacteristicSubscriptionStatus.ENABLED)
        sendSuccessResponseIfRequested(device, result, connection)
    }

    private fun handleTransmitCharacteristicSubscriptionDisabled(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        Timber.d("Device: $device UN_SUBSCRIBED to LinkConfiguration service ")
        getDataSubject(device).onNext(GattCharacteristicSubscriptionStatus.DISABLED)
        sendSuccessResponseIfRequested(device, result, connection)
    }

    private fun handlerNullDataReceived(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        Timber.d("Ignoring requestId: ${result.requestId} on service: ${result.serviceUuid} as data received is null")
        if (result.isResponseRequired) {
            // By default we do nothing when null data is received and just send success response
            sendResponse(device, connection, result.requestId, BluetoothGatt.GATT_FAILURE)
        }
    }

    private fun sendSuccessResponseIfRequested(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        if (result.isResponseRequired) {
            // By default we do nothing when null data is received and just send success response
            sendResponse(device, connection, result.requestId, BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun sendFailureResponseIfRequested(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        if (result.isResponseRequired) {
            // By default we do nothing when null data is received and just send success response
            sendResponse(device, connection, result.requestId, BluetoothGatt.GATT_FAILURE)
        }
    }

    @SuppressLint("CheckResult", "RxLeakedSubscription")
    private fun sendResponse(
        device: BluetoothDevice,
        connection: GattServerConnection,
        requestId: Int,
        status: Int,
        value: ByteArray? = null
    ) {
        Timber.d("Sending response with status: $status for device: ${device.address}")
        gattServerResponseSenderProvider.provide(connection).send(
            device = FitbitBluetoothDevice(device),
            requestId = requestId,
            status = status,
            value = value
        )
            .subscribeOn(responseScheduler)
            .subscribe(
                { Timber.d("Successfully sent $status response for Gatt server write request") },
                { Timber.e(it, "Error sending $status response for Gatt server write request") }
            )
    }


    override fun onServerCharacteristicReadRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        when (result.serviceUuid) {
            LinkConfigurationService.uuid -> handleLinkConfigurationServerCharacteristicReadRequest(
                device,
                result,
                connection
            )
            else -> Timber.d("Ignoring onServerCharacteristicReadRequest call for unsupported service: ${result.serviceUuid}")
        }
    }

    private fun handleLinkConfigurationServerCharacteristicReadRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        Timber.d(
            """
            Handle handleLinkConfigurationServerCharacteristicReadRequest call from
            device ${device.address},
            service: ${result.serviceUuid},
            characteristicUuid: ${result.characteristicUuid},
            descriptorUuid: ${result.descriptorUuid}
            """
        )
        when (result.characteristicUuid) {
            ClientPreferredConnectionModeCharacteristic.uuid -> handlePreferredConnectionModeReadRequest(
                device,
                result,
                connection
            )
            ClientPreferredConnectionConfigurationCharacteristic.uuid -> handlePreferredConnectionConfigurationReadRequest(
                device,
                result,
                connection
            )
            else -> {
                Timber.d("Ignoring onServerDescriptorWriteRequest call for unsupported characteristicUuid: ${result.characteristicUuid}")
                sendFailureResponseIfRequested(device, result, connection)
            }
        }
    }

    private fun handlePreferredConnectionModeReadRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        val linkController = linkControllerProvider.getLinkController(device)

        linkController?.let {
            sendResponse(
                device,
                connection,
                result.requestId,
                BluetoothGatt.GATT_SUCCESS,
                linkController.getPreferredConnectionMode().toByteArray()
            )
        }?: sendResponse(
            device,
            connection,
            result.requestId,
            BluetoothGatt.GATT_FAILURE
        )
    }

    private fun handlePreferredConnectionConfigurationReadRequest(
        device: BluetoothDevice,
        result: TransactionResult,
        connection: GattServerConnection
    ) {
        val linkController =
            linkControllerProvider.getLinkController(device)
        linkController?.let {
            sendResponse(
                device,
                connection,
                result.requestId,
                BluetoothGatt.GATT_SUCCESS,
                linkController.getPreferredConnectionConfiguration().toByteArray()
            )
        }?: sendResponse(
            device,
            connection,
            result.requestId,
            BluetoothGatt.GATT_FAILURE
        )
    }

}
