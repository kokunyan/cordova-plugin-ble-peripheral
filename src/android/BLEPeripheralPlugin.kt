// (c) 2018 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.apache.cordova.ble_peripheral

import ai.doma.miniappdemo.ext.logD
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaArgs
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.LOG
import org.apache.cordova.PermissionHelper
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays
import java.util.Hashtable
import java.util.UUID


data class BLECharacteristicReadRequest(
    var contextID: UUID,
    var timeoutJob: Job,
    var device: BluetoothDevice,
    var requestId: Int,
    var offset: Int,
    var characteristic: BluetoothGattCharacteristic
)

@SuppressLint("MissingPermission,LogNotTimber")
class BLEPeripheralPlugin : CordovaPlugin() {
    // callbacks
    private var enableBluetoothCallback: CallbackContext? = null
    private var characteristicValueChangedCallback: CallbackContext? = null
    private var characteristicValueRequestedCallback: CallbackContext? = null
    private var advertisingStartedCallback: CallbackContext? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private val services: MutableMap<UUID, BluetoothGattService> = HashMap()
    private val registeredDevices: MutableSet<BluetoothDevice> = HashSet()

    // Bluetooth state notification
    private var stateCallback: CallbackContext? = null
    private var stateReceiver: BroadcastReceiver? = null
    private val bluetoothStates: Map<Int, String> = object : Hashtable<Int, String>() {
        init {
            put(BluetoothAdapter.STATE_OFF, "off")
            put(BluetoothAdapter.STATE_TURNING_OFF, "turningOff")
            put(BluetoothAdapter.STATE_ON, "on")
            put(BluetoothAdapter.STATE_TURNING_ON, "turningOn")
        }
    }
    private var permissionCallback: CallbackContext? = null
    private var action: String? = null
    private var args: CordovaArgs? = null
    private var bluetoothManager: BluetoothManager? = null

    val scope = CoroutineScope(Dispatchers.IO)
    protected fun finalize() {
        scope.cancel()
    }
    private var bLECharacteristicReadRequestMap = mutableMapOf<UUID, BLECharacteristicReadRequest>()
    private var diagnosticJob: Job? = null
    private val diagnosticInterval = 5000L // 5 seconds between diagnostics

    // Add counters for events in class to track statistics
    private var connectionAttempts = 0
    private var readRequests = 0
    private var writeRequests = 0
    private var notificationSubscriptions = 0

    // Flag for tracking advertising state
    private var isAdvertising = false

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        Log.d("BLEPeripheral", "Plugin initialized")
        checkManifestPermissions()
    }

    override fun pluginInitialize() {
        Log.d("BLEPeripheral", "pluginInitialize called")
        if (COMPILE_SDK_VERSION == -1) {
            val context = cordova.context
            COMPILE_SDK_VERSION = context.applicationContext.applicationInfo.targetSdkVersion
            Log.d("BLEPeripheral", "COMPILE_SDK_VERSION set to $COMPILE_SDK_VERSION")
        }
    }

    override fun onDestroy() {
        Log.d("BLEPeripheral", "onDestroy called")
        stopPeriodicDiagnostics()
        removeStateListener()
    }

    override fun onReset() {
        Log.d("BLEPeripheral", "onReset called")
        removeStateListener()
    }

    @Throws(JSONException::class)
    override fun execute(
        action: String,
        args: CordovaArgs,
        callbackContext: CallbackContext
    ): Boolean {
        Log.d("BLEPeripheral", "execute called with action = $action and args = $args")

        //"initial setup" without bt permissions needed
        if (bluetoothAdapter == null) {
            val activity: Activity = cordova.activity
            bluetoothManager =
                activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (bluetoothManager == null) {
                LOG.e("BLEPeripheral", "bluetoothManager is null")
                callbackContext.error("Unable to get the Bluetooth Manager")
                return false
            }
            bluetoothAdapter = bluetoothManager!!.adapter
            Log.d("BLEPeripheral", "Bluetooth adapter initialized")
        }
        if (action == SET_CHARACTERISTIC_VALUE_CHANGED_LISTENER) {
            Log.d("BLEPeripheral", "Setting characteristic value changed listener")
            characteristicValueChangedCallback = callbackContext
            return true
        } else if (action == SET_CHARACTERISTIC_VALUE_REQUESTED_LISTENER) {
            Log.d("BLEPeripheral", "Setting characteristic value requested listener")
            characteristicValueRequestedCallback = callbackContext
            return true
        } else if (action == RECEIVE_REQUESTED_CHARACTERISTIC_VALUE) {
            Log.d("BLEPeripheral", "Receiving requested characteristic value")
            val uniqueUUID = args.optString(0)

            val result = args.optString(1)?.let { Base64.decode(it, Base64.DEFAULT) }
            Log.v("BLEPeripheral", "receiveRequestedCharacteristicValue: $result")
            if (uniqueUUID == null || result == null) {
                callbackContext.error("uniqueUUID or result is null")
                return true
            }
            val contextID = UUIDHelper.uuidFromString(uniqueUUID)
            val requestContext = bLECharacteristicReadRequestMap[contextID]
            if (requestContext == null) {
                Log.d("BLEPeripheral", "Request context not found, possibly timed out")
                return true
            }
            bLECharacteristicReadRequestMap.remove(contextID)
            requestContext.timeoutJob.cancel()
            val cbuuid = requestContext.characteristic.uuid
            val service = requestContext.characteristic.service
            if (service == null) {
                callbackContext.error("service is null")
                return true
            }

            val characteristic = requestContext.characteristic
            characteristic.value = result.drop(requestContext.offset).toByteArray()
            Log.d("BLEPeripheral", "Characteristic value set: ${characteristic.value}")

            gattServer!!.sendResponse(
                requestContext.device,
                requestContext.requestId,
                BluetoothGatt.GATT_SUCCESS,
                requestContext.offset,
                characteristic.value
            )
            Log.d("BLEPeripheral", "Response sent for characteristic read request")

            callbackContext.success()
        } else if (action == SET_BLUETOOTH_STATE_CHANGED_LISTENER) {
            Log.d("BLEPeripheral", "Setting Bluetooth state changed listener")
            if (stateCallback != null) {
                callbackContext.error("State callback already registered.")
            } else {
                stateCallback = callbackContext
                addStateListener()
                sendBluetoothStateChange(bluetoothAdapter!!.state)
            }
            return true
        }

        //everything below here needs bt connect permissions
        val hasConnectPermission = PermissionHelper.hasPermission(this, BLUETOOTH_CONNECT)
        if (!hasConnectPermission && COMPILE_SDK_VERSION >= 31 && Build.VERSION.SDK_INT >= 31) {
            permissionCallback = callbackContext
            this.action = action
            this.args = args
            PermissionHelper.requestPermission(this, REQUEST_BLUETOOTH_CONNECT, BLUETOOTH_CONNECT)
            Log.d("BLEPeripheral", "Requesting Bluetooth connect permission")
            return true
        }
        if (gattServer == null) {
            Log.d("BLEPeripheral", "Initializing GATT server")
            val activity: Activity = cordova.activity
            val hardwareSupportsBLE = activity.applicationContext
                .packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            if (!hardwareSupportsBLE) {
                LOG.e("BLEPeripheral", "This hardware does not support Bluetooth Low Energy")
                callbackContext.error("This hardware does not support Bluetooth Low Energy")
                return false
            }
            val hardwareSupportsPeripherals = bluetoothAdapter!!.isMultipleAdvertisementSupported
            if (!hardwareSupportsPeripherals) {
                val errorMessage =
                    "This hardware does not support creating Bluetooth Low Energy peripherals"
                LOG.e("BLEPeripheral", errorMessage)
                callbackContext.error(errorMessage)
                return false
            }

            //bluetooth connect permission
            gattServer = bluetoothManager!!.openGattServer(cordova.context, gattServerCallback)
            Log.d("BLEPeripheral", "GATT server opened")
        }
        return when (action) {
            CREATE_SERVICE -> {
                Log.d("BLEPeripheral", "Creating service")
                val serviceUUID = uuidFromString(args.getString(0))
                val service = BluetoothGattService(
                    serviceUUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                )
                services[serviceUUID] = service
                Log.d("BLEPeripheral", "Service created with UUID: $serviceUUID")
                callbackContext.success()
                true
            }
            REMOVE_SERVICE -> {
                Log.d("BLEPeripheral", "Removing service")
                val serviceUUID = uuidFromString(args.getString(0))
                val service = services[serviceUUID]
                if (service == null) {
                    callbackContext.error("Service $serviceUUID not found")
                    return true
                }
                val success = gattServer!!.removeService(service)
                if (success) {
                    services.remove(serviceUUID)
                    Log.d("BLEPeripheral", "Service removed: $serviceUUID")
                    callbackContext.success()
                } else {
                    callbackContext.error("Error removing $serviceUUID to GATT Server")
                }
                true
            }
            REMOVE_ALL_SERVICES -> {
                Log.d("BLEPeripheral", "Removing all services")
                gattServer!!.clearServices()
                callbackContext.success()
                true
            }
            ADD_CHARACTERISTIC -> {
                Log.d("BLEPeripheral", "Adding characteristic")
                val serviceUUID = uuidFromString(args.getString(0))
                val characteristicUUID = uuidFromString(args.getString(1))
                val properties = args.getInt(2)
                val permissions = args.getInt(3)
                val characteristic = BluetoothGattCharacteristic(
                    characteristicUUID,
                    properties,
                    permissions
                )
                val service = services[serviceUUID]
                if (service == null) {
                    callbackContext.error("service not found")
                    return true
                }
                service.addCharacteristic(characteristic)
                Log.d("BLEPeripheral", "Characteristic added: $characteristicUUID to service: $serviceUUID")

                // If notify or indicate, we need to add the 2902 descriptor
                if (isNotify(characteristic) || isIndicate(characteristic)) {
                    characteristic.addDescriptor(createClientCharacteristicConfigurationDescriptor(characteristic))
                    Log.d("BLEPeripheral", "Descriptor added for notifications/indications")
                }
                callbackContext.success()
                true
            }
            CREATE_SERVICE_FROM_JSON -> {
                Log.d("BLEPeripheral", "Creating service from JSON")
                val json = args.getJSONObject(0)
                Log.d("BLEPeripheral", "Received JSON: ${json.toString()}")
                try {
                    val serviceUUID = uuidFromString(json.getString("uuid"))
                    Log.d("BLEPeripheral", "Creating service $serviceUUID")
                    val service =
                        BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                    val characteristicArray = json.getJSONArray("characteristics")
                    for (i in 0 until characteristicArray.length()) {
                        val jsonObject = characteristicArray.getJSONObject(i)
                        val uuid = uuidFromString(jsonObject.getString("uuid"))
                        val properties = jsonObject.getInt("properties")
                        val permissions = jsonObject.getInt("permissions")
                        Log.d(
                            "BLEPeripheral",
                            "Adding characteristic $uuid properties=$properties permissions=$permissions"
                        )
                        val characteristic = BluetoothGattCharacteristic(uuid, properties, permissions)

                        // If notify or indicate, add the 2902 descriptor
                        if (isNotify(characteristic) || isIndicate(characteristic)) {
                            characteristic.addDescriptor(createClientCharacteristicConfigurationDescriptor(characteristic))
                            Log.d("BLEPeripheral", "Descriptor added for notifications/indications")
                        }

                        // Check if descriptors array exists in JSON
                        if (jsonObject.has("descriptors")) {
                            val descriptorsArray = jsonObject.getJSONArray("descriptors")
                            for (j in 0 until descriptorsArray.length()) {
                                val jsonDescriptor = descriptorsArray.getJSONObject(j)
                                val descriptorUUID = uuidFromString(jsonDescriptor.getString("uuid"))

                                // Get descriptor permissions from JSON, default to READ if not specified
                                val descriptorPermissions = if (jsonDescriptor.has("permissions")) {
                                    jsonDescriptor.getInt("permissions")
                                } else {
                                    BluetoothGattDescriptor.PERMISSION_READ
                                }

                                // future versions need to handle more than Strings
                                val descriptorValue = jsonDescriptor.getString("value")
                                Log.d(
                                    "BLEPeripheral",
                                    "Adding descriptor $descriptorUUID permissions=$descriptorPermissions value=$descriptorValue"
                                )
                                val descriptor =
                                    BluetoothGattDescriptor(descriptorUUID, descriptorPermissions)
                                if (!characteristic.addDescriptor(descriptor)) {
                                    callbackContext.error("Failed to add descriptor $descriptorValue")
                                    return true
                                }
                                if (!descriptor.setValue(descriptorValue.toByteArray())) {
                                    callbackContext.error("Failed to set descriptor value to $descriptorValue")
                                    return true
                                }
                            }
                        }
                        service.addCharacteristic(characteristic)
                    }
                    services[serviceUUID] = service
                    if (gattServer!!.addService(service)) {
                        Log.d("BLEPeripheral", "Successfully added service $serviceUUID")
                        callbackContext.success()
                    } else {
                        callbackContext.error("Error adding " + service.uuid + " to GATT Server")
                    }
                } catch (e: JSONException) {
                    LOG.e("BLEPeripheral", "Invalid JSON for Service", e)
                    e.printStackTrace()
                    callbackContext.error(e.message)
                }
                true
            }
            PUBLISH_SERVICE -> {
                Log.d("BLEPeripheral", "Publishing service")
                val serviceUUID = uuidFromString(args.getString(0))
                val service = services[serviceUUID]
                if (service == null) {
                    callbackContext.error("Service $serviceUUID not found")
                    return true
                }
                val success = gattServer!!.addService(service)
                if (success) {
                    Log.d("BLEPeripheral", "Service published: $serviceUUID")
                    callbackContext.success()
                } else {
                    callbackContext.error("Error adding $serviceUUID to GATT Server")
                }
                true
            }
            START_ADVERTISING -> {
                Log.d("BLEPeripheral", "Starting advertising")
                val hasAdvertisePermission = PermissionHelper.hasPermission(this, BLUETOOTH_ADVERTISE)
                val hasScanPermission = PermissionHelper.hasPermission(this, BLUETOOTH_SCAN)
                
                if ((!hasAdvertisePermission || !hasScanPermission) && COMPILE_SDK_VERSION >= 31 && Build.VERSION.SDK_INT >= 31) {
                    permissionCallback = callbackContext
                    this.action = action
                    this.args = args
                    
                    if (!hasAdvertisePermission) {
                        PermissionHelper.requestPermission(this, REQUEST_BLUETOOTH_ADVERTISE, BLUETOOTH_ADVERTISE)
                        Log.d("BLEPeripheral", "Requesting Bluetooth advertise permission")
                    } else if (!hasScanPermission) {
                        PermissionHelper.requestPermission(this, REQUEST_BLUETOOTH_SCAN, BLUETOOTH_SCAN)
                        Log.d("BLEPeripheral", "Requesting Bluetooth scan permission")
                    }
                    return true
                }
                val advertisedName = args.getString(1)
                val serviceUUID = uuidFromString(args.getString(0))
                bluetoothAdapter!!.name = advertisedName
                val bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
                val advertisementData = getAdvertisementData(serviceUUID)
                val advertiseSettings = advertiseSettings
                try {
                    // First try using programmatic mode
                    setProgrammaticDiscoverableMode()
                    // If it doesn't work, request through UI
                    if (bluetoothAdapter?.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                        requestDiscoverableMode()
                    }
                    
                    bluetoothLeAdvertiser.startAdvertising(
                        advertiseSettings,
                        advertisementData,
                        advertiseCallback
                    )
                } catch (e: Exception) {
                    // In case of error, try simplified advertisment
                    Log.e("BLEPeripheral", "Error starting advertisment, trying simplified format", e)
                    val simplifiedData = getSimplifiedAdvertisementData(serviceUUID)
                    bluetoothLeAdvertiser.startAdvertising(
                        advertiseSettings,
                        simplifiedData,
                        advertiseCallback
                    )
                }
                logBluetoothDiagnostics()
                startPeriodicDiagnostics()
                Log.d("BLEPeripheral", "Advertising started with service UUID: $serviceUUID")
                Log.d("BLEPeripheral", "!!! IMPORTANT !!! Ensure the reader is searching for this service UUID: $serviceUUID")
                advertisingStartedCallback = callbackContext
                true
            }
            STOP_ADVERTISING -> {
                Log.d("BLEPeripheral", "Stopping advertising")
                val hasAdvertisingPermission = PermissionHelper.hasPermission(this, BLUETOOTH_ADVERTISE)
                if (!hasAdvertisingPermission && COMPILE_SDK_VERSION >= 31 && Build.VERSION.SDK_INT >= 31) {
                    permissionCallback = callbackContext
                    this.action = action
                    this.args = args
                    PermissionHelper.requestPermission(
                        this,
                        REQUEST_BLUETOOTH_ADVERTISE,
                        BLUETOOTH_ADVERTISE
                    )
                    Log.d("BLEPeripheral", "Requesting Bluetooth advertise permission")
                    return true
                }
                val bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                isAdvertising = false
                stopPeriodicDiagnostics()
                Log.d("BLEPeripheral", "!!!IMPORTANT!!! Advertising stopped")
                callbackContext.success()
                true
            }
            SET_CHARACTERISTIC_VALUE -> {
                Log.d("BLEPeripheral", "Setting characteristic value")
                val serviceUUID = uuidFromString(args.getString(0))
                val characteristicUUID = uuidFromString(args.getString(1))
                val value = args.getArrayBuffer(2)
                val service = services[serviceUUID]
                if (service == null) {
                    callbackContext.error("Service $serviceUUID not found")
                    return true
                }
                val characteristic = service.getCharacteristic(characteristicUUID)
                if (characteristic == null) {
                    callbackContext.error("Characteristic $characteristicUUID not found on service $serviceUUID")
                    return true
                }
                characteristic.value = value
                Log.d("BLEPeripheral", "Characteristic value set for $characteristicUUID: ${Arrays.toString(value)}")
                if (isNotify(characteristic) || isIndicate(characteristic)) {
                    notifyRegisteredDevices(characteristic)
                    Log.d("BLEPeripheral", "Notified registered devices for characteristic $characteristicUUID")
                }
                callbackContext.success()
                true
            }
            SETTINGS -> {
                Log.d("BLEPeripheral", "Opening Bluetooth settings")
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                cordova.activity.startActivity(intent)
                callbackContext.success()
                true
            }
            ENABLE -> {
                Log.d("BLEPeripheral", "Enabling Bluetooth")
                enableBluetoothCallback = callbackContext
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                cordova.startActivityForResult(this, intent, REQUEST_ENABLE_BLUETOOTH)
                true
            }
            GET_BLUETOOTH_SYSTEM_STATE -> {
                Log.d("BLEPeripheral", "Getting Bluetooth system state")
                val bleState = getBleState()
                Log.d("BLEPeripheral", "Bluetooth system state: $bleState")
                callbackContext.success(bleState)
                true
            }
            START_SENDING_STASHED_READ_WRITE_EVENTS -> {
                Log.d("BLEPeripheral", "Starting to send stashed read/write events")
                true
            }
            SETUP_READER_COMMUNICATION -> {
                // These UUIDs must match the attribute table
                val serviceUUID = uuidFromString(args.getString(0))
                val toReaderCharUUID = uuidFromString(args.getString(1)) 
                val fromReaderCharUUID = uuidFromString(args.getString(2))
                
                // Configure service structure
                setupSpecificServiceStructure(serviceUUID, toReaderCharUUID, fromReaderCharUUID)
                
                // Request FULL mode visibility
                forceDiscoverableMode()
                
                // Try using new API first
                if (!startModernAdvertising(serviceUUID)) {
                    // If new API is not available, use old method
                    startLegacyAdvertising(serviceUUID)
                }
                
                // Log detailed advertising information
                logAdvertisingDetails(serviceUUID)
                
                callbackContext.success()
                return true
            }
            "sendDataToReader" -> {
                val serviceUUID = uuidFromString(args.getString(0))
                val charUUID = uuidFromString(args.getString(1))
                val dataBase64 = args.getString(2)
                val data = Base64.decode(dataBase64, Base64.DEFAULT)
                
                val success = sendDataToReader(serviceUUID, charUUID, data)
                if (success) {
                    callbackContext.success()
                } else {
                    callbackContext.error("Failed to send data. Check UUID or connected devices.")
                }
                return true
            }
            else -> {
                Log.d("BLEPeripheral", "Unknown action: $action")
                false
            }
        }
    }

    fun getBleState(): JSONObject {
        val jsonResult = JSONObject()
        val jsonServices = JSONArray()

        gattServer?.services
            ?.forEach {
                val jsonService = JSONObject()
                jsonService.put("uuid", it.uuid.toString())
                jsonService.put("isPrimary", it.type == BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val jsonCharacteristics = JSONArray()
                it.characteristics.forEach { it ->
                    val jsonCharacteristic = JSONObject()
                    jsonCharacteristic.put("uuid", it.uuid.toString())
                    jsonCharacteristic.put("properties", it.properties.toString())
                    jsonCharacteristic.put("permissions", it.permissions.toString())
                    jsonCharacteristics.put(jsonCharacteristic)
                }
                jsonService.put("characteristics", jsonCharacteristics)
                jsonServices.put(jsonService)
            }
        jsonResult.put("services", jsonServices)
        Log.d("BLEPeripheral", "Current BLE state: $jsonResult")
        return jsonResult
    }

    private fun onBluetoothStateChange(intent: Intent) {
        Log.d("BLEPeripheral", "Bluetooth state change detected")
        val action = intent.action
        if (action != null && action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d("BLEPeripheral", "Bluetooth state changed to: ${bluetoothStates[state]}")
            sendBluetoothStateChange(state)
        }
    }

    private fun sendBluetoothStateChange(state: Int) {
        Log.d("BLEPeripheral", "Sending Bluetooth state change: ${bluetoothStates[state]}")
        if (stateCallback != null) {
            val result = PluginResult(PluginResult.Status.OK, bluetoothStates[state])
            result.keepCallback = true
            stateCallback!!.sendPluginResult(result)
        }
    }

    private fun addStateListener() {
        Log.d("BLEPeripheral", "Adding state listener")
        if (stateReceiver == null) {
            stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onBluetoothStateChange(intent)
                }
            }
        }
        try {
            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            webView.context.registerReceiver(stateReceiver, intentFilter)
            Log.d("BLEPeripheral", "State listener registered")
        } catch (e: Exception) {
            LOG.e("BLEPeripheral", "Error registering state receiver: " + e.message, e)
        }
    }

    private fun removeStateListener() {
        Log.d("BLEPeripheral", "Removing state listener")
        if (stateReceiver != null) {
            try {
                webView.context.unregisterReceiver(stateReceiver)
                Log.d("BLEPeripheral", "State listener unregistered")
            } catch (e: Exception) {
                LOG.e("BLEPeripheral", "Error un-registering state receiver: " + e.message, e)
            }
        }
        stateCallback = null
        stateReceiver = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        Log.d("BLEPeripheral", "onActivityResult called with requestCode = $requestCode, resultCode = $resultCode")
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d("BLEPeripheral", "User enabled Bluetooth")
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback!!.success()
                }
            } else {
                Log.d("BLEPeripheral", "User did *NOT* enable Bluetooth")
                if (enableBluetoothCallback != null) {
                    enableBluetoothCallback!!.error("User did not enable Bluetooth")
                }
            }
            enableBluetoothCallback = null
        }
    }

    private val gattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                // Increment counter if device is trying to connect
                if (newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_CONNECTING) {
                    connectionAttempts++
                    Log.d("BLEPeripheral", "!!! CONNECTION ATTEMPT !!! from device ${device.address} (${device.name})")
                }
                
                val statusStr = when(status) {
                    BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
                    else -> "ERROR: $status"
                }
                
                val stateStr = when(newState) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> "UNKNOWN: $newState"
                }
                
                Log.d("BLEPeripheral", "Connection state change for device ${device.address} (${device.name}): status=$statusStr, new state=$stateStr")
                
                // If device is connected, add additional information
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLEPeripheral", "Device connected! Connection type: ${device.type}, " +
                          "Device class: ${device.bluetoothClass?.majorDeviceClass}")
                    sendConnectionEvent("connected", device)
                }
                
                super.onConnectionStateChange(device, status, newState)
                
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    registeredDevices.remove(device)
                    Log.d("BLEPeripheral", "Device disconnected and removed from registered devices list")
                    sendConnectionEvent("disconnected", device)
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                Log.d("BLEPeripheral", "Service added: $service with status $status")
                super.onServiceAdded(status, service)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                readRequests++
                Log.d("BLEPeripheral", "!!! READ REQUEST !!! from device ${device.address} (${device.name})")
                Log.d("BLEPeripheral", "Requested characteristic: ${characteristic.uuid}")
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

                if (characteristicValueRequestedCallback != null) {
                    val contextID = UUID.randomUUID()
                    bLECharacteristicReadRequestMap[contextID] = BLECharacteristicReadRequest(
                        contextID = contextID,
                        device = device,
                        requestId = requestId,
                        offset = offset,
                        characteristic = characteristic,
                        timeoutJob = scope.launch {
                            delay(3000)
                            bLECharacteristicReadRequestMap.remove(contextID)
                            logD { "onCharacteristicReadRequest fallback requestId=$requestId offset=$offset" }
                            gattServer!!.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                characteristic.value
                            )
                            Log.d("BLEPeripheral", "Fallback response sent for read request")
                        }
                    )
                    val map = mapOf(
                        "contextID" to contextID.toString(),
                        "characteristic" to characteristic.uuid.toString(),
                        "service" to characteristic.service.uuid.toString()
                    )
                    val json = JSONObject(map)
                    val pluginResult = PluginResult(PluginResult.Status.OK, json)
                    pluginResult.keepCallback = true
                    characteristicValueRequestedCallback?.sendPluginResult(pluginResult)
                    Log.d("BLEPeripheral", "Characteristic read request sent to callback")
                } else {
                    Log.d("BLEPeripheral", "onCharacteristicReadRequest requestId=$requestId offset=$offset")
                    gattServer!!.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        characteristic.value
                    )
                    Log.d("BLEPeripheral", "Response sent for characteristic read request")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                writeRequests++
                Log.d("BLEPeripheral", "!!! WRITE REQUEST !!! from device ${device.address} (${device.name})")
                Log.d("BLEPeripheral", "Write to characteristic: ${characteristic.uuid}, value: ${Arrays.toString(value)}")
                super.onCharacteristicWriteRequest(
                    device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )
                Log.d(
                    "BLEPeripheral",
                    "onCharacteristicWriteRequest characteristic=" + characteristic.uuid + " value=" + Arrays.toString(
                        value
                    )
                )
                if (characteristicValueChangedCallback != null) {
                    try {
                        val message = JSONObject()
                        message.put("service", characteristic.service.uuid.toString())
                        message.put("characteristic", characteristic.uuid.toString())
                        message.put("value", byteArrayToJSON(value))
                        val result = PluginResult(PluginResult.Status.OK, message)
                        result.keepCallback = true
                        characteristicValueChangedCallback!!.sendPluginResult(result)
                        Log.d("BLEPeripheral", "Characteristic write request sent to callback")
                    } catch (e: JSONException) {
                        LOG.e("BLEPeripheral", "JSON encoding failed in onCharacteristicWriteRequest", e)
                    }
                }
                if (responseNeeded) {
                    gattServer!!.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                    Log.d("BLEPeripheral", "Response sent for characteristic write request")
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                Log.d("BLEPeripheral", "Notification sent to device $device with status $status")
                super.onNotificationSent(device, status)
                Log.d("BLEPeripheral", "onNotificationSent device=$device status=$status")
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                super.onDescriptorReadRequest(device, requestId, offset, descriptor)
                Log.d("BLEPeripheral", "onDescriptorReadRequest for ${descriptor.uuid} from ${device.address}")
                
                // Special handling for client configuration descriptor (CCCD)
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                    val currentValue = interpretCCCDValue(descriptor)
                    Log.d("BLEPeripheral", "CCCD current value: $currentValue")
                    
                    // If descriptor is empty, initialize it with default value
                    if (descriptor.value == null || descriptor.value.isEmpty()) {
                        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        Log.d("BLEPeripheral", "CCCD initialized with default value: disabled")
                    }
                }
                
                // Send response to client with current descriptor value
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    descriptor.value
                )
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
                
                Log.d("BLEPeripheral", "onDescriptorWriteRequest for ${descriptor.uuid} from ${device.address}")
                
                // Processing of write to client configuration descriptor
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                    Log.d("BLEPeripheral", "Write to CCCD: ${value.joinToString(" ") { "%02X".format(it) }}")
                    
                    // Interpretation and logging of requested value
                    val configDescription = when {
                        Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                            "Disabling notifications/indications"
                        }
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                            notificationSubscriptions++
                            "Enabling notifications"
                        }
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                            notificationSubscriptions++
                            "Enabling indications"
                        }
                        value.size >= 2 && value[0].toInt() == 3 && value[1].toInt() == 0 -> {
                            notificationSubscriptions++
                            "Enabling notifications and indications"
                        }
                        else -> "Unknown value: ${value.joinToString(" ") { "%02X".format(it) }}"
                    }
                    Log.d("BLEPeripheral", "CCCD interpretation: $configDescription")
                    
                    // Apply new value to descriptor
                    descriptor.value = value
                }
                
                // Send response to client if needed
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                Log.d("BLEPeripheral", "Execute write: device=$device, requestId=$requestId, execute=$execute")
                super.onExecuteWrite(device, requestId, execute)
                Log.d("BLEPeripheral", "onExecuteWrite")
            }
        }

    // https://github.com/don/uribeacon/blob/58c31cf28d06a80880b0ed46b005204821fd623f/beacons/android/app/src/main/java/org/uribeacon/example/beacon/UriBeaconAdvertiserActivity.java
    private fun getAdvertisementData(serviceUuid: UUID): AdvertiseData {
        val builder = AdvertiseData.Builder()
        
        // Include transmission power level - may help reader determine distance
        builder.setIncludeTxPowerLevel(true)
        
        // Add service UUID with higher visibility
        builder.addServiceUuid(ParcelUuid(serviceUuid))
        
        // Important! Device name in advertisment
        builder.setIncludeDeviceName(true)
        
        // Important! Advertisment service UUID: $serviceUuid
        Log.d("BLEPeripheral", "IMPORTANT! Advertisment service UUID: $serviceUuid")
        Log.d("BLEPeripheral", "IMPORTANT! Device name in advertisment: ${bluetoothAdapter?.name}")
        
        val result = builder.build()
        return result
    }

    private val advertiseSettings: AdvertiseSettings
        private get() {
            val builder = AdvertiseSettings.Builder()
            // Use maximum frequency for better discovery
            builder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            // Use maximum transmission power
            builder.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Make advertisment connectable
            builder.setConnectable(true)
            // No timeout
            builder.setTimeout(0)
            
            Log.d("BLEPeripheral", "Created advertisement settings: mode=LOW_LATENCY, power=HIGH, connectable=true, no timeout")
            return builder.build()
        }
    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            isAdvertising = true
            Log.d("BLEPeripheral", "!!!IMPORTANT!!! Advertisment SUCCESSFULLY started with settings: mode=${settingsInEffect.mode}, " +
                  "txPowerLevel=${settingsInEffect.txPowerLevel}, " +
                  "connectable=${settingsInEffect.isConnectable}")
            if (advertisingStartedCallback != null) {
                advertisingStartedCallback!!.success()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            isAdvertising = false
            val errorMessage = when(errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertisment already started"
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Too much data for advertisment"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error: $errorCode"
            }
            Log.e("BLEPeripheral", "!!!IMPORTANT!!! Error starting advertisment: $errorMessage")
            if (advertisingStartedCallback != null) {
                advertisingStartedCallback!!.error(errorCode)
            }
        }
    }

    private fun notifyRegisteredDevices(characteristic: BluetoothGattCharacteristic) {
        val confirm = isIndicate(characteristic)
        for (device in registeredDevices) {
            gattServer!!.notifyCharacteristicChanged(device, characteristic, confirm)
        }
    }

    // Utils
    private fun uuidFromString(uuid: String): UUID {
        return UUIDHelper.uuidFromString(uuid)
    }

    private fun isNotify(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    }

    private fun isIndicate(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }

    @Throws(JSONException::class)
    private fun byteArrayToJSON(bytes: ByteArray): JSONObject {
        val `object` = JSONObject()
        `object`.put("CDVType", "ArrayBuffer")
        `object`.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return `object`
    }

    // Add method for creating a CCCD descriptor with correct permissions
    private fun createClientCharacteristicConfigurationDescriptor(characteristic: BluetoothGattCharacteristic): BluetoothGattDescriptor {
        Log.d("BLEPeripheral", "Creating CCCD descriptor for characteristic ${characteristic.uuid}")
        
        // Configure descriptor permissions based on characteristic permissions
        val permissions = when {
            // Rule 1: If the characteristic has READ permission, the descriptor must have WRITE permission
            (characteristic.permissions and BluetoothGattCharacteristic.PERMISSION_READ) != 0 -> {
                Log.d("BLEPeripheral", "Characteristic has READ permission, setting WRITE for the descriptor")
                BluetoothGattDescriptor.PERMISSION_WRITE
            }
            // Rule 2: If the characteristic has WRITE permission, the descriptor must have READ permission
            (characteristic.permissions and BluetoothGattCharacteristic.PERMISSION_WRITE) != 0 -> {
                Log.d("BLEPeripheral", "Characteristic has WRITE permission, setting READ for the descriptor")
                BluetoothGattDescriptor.PERMISSION_READ
            }
            // By default, allow both read and write for maximum compatibility
            else -> {
                Log.d("BLEPeripheral", "For characteristics without explicit permissions, setting READ|WRITE for the descriptor")
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            }
        }
        
        // If the characteristic has notify or indicate properties, the descriptor must always be writable
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d("BLEPeripheral", "Characteristic has NOTIFY/INDICATE properties, adding WRITE permission")
            return BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        }
        
        Log.d("BLEPeripheral", "Created descriptor with permissions: $permissions")
        
        return BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
            permissions
        )
    }

    //TODO: Update permission handling when cdv-android platform is updated
    override fun onRequestPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val callback = popPermissionsCallback()
        val action = action
        val args = args
        this.action = null
        this.args = null
        if (callback == null) {
            if (grantResults.size > 0) {
                // There are some odd happenings if permission requests are made while booting up capacitor
                LOG.w("BLEPeripheral", "onRequestPermissionResult received with no pending callback")
            }
            return
        }
        if (grantResults.size == 0) {
            callback.error("No permissions not granted.")
            return
        }
        for (i in permissions.indices) {
            if (permissions[i] == BLUETOOTH_CONNECT && grantResults[i] == PackageManager.PERMISSION_DENIED) {
                Log.d("BLEPeripheral", "User *rejected* Bluetooth_Connect Access")
                callback.error("Bluetooth Connect permission not granted.")
                return
            } else if (permissions[i] == BLUETOOTH_ADVERTISE && grantResults[i] == PackageManager.PERMISSION_DENIED) {
                Log.d("BLEPeripheral", "User *rejected* Bluetooth_Advertise Access")
                callback.error("Bluetooth Advertise permission not granted.")
                return
            } else if (permissions[i] == BLUETOOTH_SCAN && grantResults[i] == PackageManager.PERMISSION_DENIED) {
                Log.d("BLEPeripheral", "User *rejected* Bluetooth_Scan Access")
                callback.error("Bluetooth Scan permission not granted.")
                return
            }
        }
        try {
            execute(action!!, args!!, callback)
        } catch (e: JSONException) {
            callback.error(e.message)
        }
    }

    private fun popPermissionsCallback(): CallbackContext? {
        val callback = permissionCallback
        permissionCallback = null
        return callback
    }

    // New function for displaying statistics
    private fun logConnectionStatistics() {
        Log.d("BLEPeripheral", "=== BLE CONNECTION STATISTICS ===")
        Log.d("BLEPeripheral", "Connection attempts: $connectionAttempts")
        Log.d("BLEPeripheral", "Read requests: $readRequests")
        Log.d("BLEPeripheral", "Write requests: $writeRequests")
        Log.d("BLEPeripheral", "Notification subscriptions: $notificationSubscriptions")
        Log.d("BLEPeripheral", "Active devices: ${registeredDevices.size}")
        
        // Check Bluetooth state
        bluetoothAdapter?.let {
            Log.d("BLEPeripheral", "Bluetooth state: ${bluetoothStates[it.state]}")
            Log.d("BLEPeripheral", "Bluetooth visibility mode: ${it.scanMode}")
            val scanModeStr = when(it.scanMode) {
                BluetoothAdapter.SCAN_MODE_NONE -> "INVISIBLE"
                BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE (BUT NOT VISIBLE)"
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "FULLY VISIBLE"
                else -> "UNKNOWN (${it.scanMode})"
            }
            Log.d("BLEPeripheral", "Bluetooth visibility mode: $scanModeStr")
            
            if (it.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                Log.d("BLEPeripheral", "!!! WARNING !!! Device may not be fully visible to all BLE scanners")
            }
        }
        
        // Check availability of advertisment
        if (bluetoothAdapter?.bluetoothLeAdvertiser == null) {
            Log.e("BLEPeripheral", "!!!CRITICAL ERROR!!! BluetoothLeAdvertiser unavailable!")
        } else {
            Log.d("BLEPeripheral", "BluetoothLeAdvertiser available")
        }
        
        Log.d("BLEPeripheral", "===============================")
    }

    private fun logBluetoothDiagnostics() {
        Log.d("BLEPeripheral", "==== BLUETOOTH DIAGNOSTICS ====")
        Log.d("BLEPeripheral", "Bluetooth enabled: ${bluetoothAdapter?.isEnabled == true}")
        Log.d("BLEPeripheral", "Device name: ${bluetoothAdapter?.name}")
        Log.d("BLEPeripheral", "MAC address: ${bluetoothAdapter?.address}")
        Log.d("BLEPeripheral", "Scan mode: ${getScanModeString(bluetoothAdapter?.scanMode)}")
        Log.d("BLEPeripheral", "Advertising active: $isAdvertising")
        Log.d("BLEPeripheral", "Number of services: ${services.size}")
        Log.d("BLEPeripheral", "Connected devices: ${registeredDevices.size}")
        Log.d("BLEPeripheral", "Connection attempts: $connectionAttempts")
        Log.d("BLEPeripheral", "Read requests: $readRequests")
        Log.d("BLEPeripheral", "Write requests: $writeRequests")
        Log.d("BLEPeripheral", "Notification subscriptions: $notificationSubscriptions")
        Log.d("BLEPeripheral", "===============================")
    }

    // Helper method for displaying scan mode
    private fun getScanModeString(scanMode: Int?): String {
        return when(scanMode) {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "CONNECTABLE_DISCOVERABLE (fully visible)"
            BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE (connectable, but not visible)"
            BluetoothAdapter.SCAN_MODE_NONE -> "NONE (not visible and not connectable)"
            else -> "UNKNOWN ($scanMode)"
        }
    }

    private fun startPeriodicDiagnostics() {
        stopPeriodicDiagnostics()
        
        Log.d("BLEPeripheral", "Starting periodic diagnostics with interval $diagnosticInterval ms")
        
        diagnosticJob = scope.launch {
            while (isActive) {
                logBluetoothDiagnostics()
                delay(diagnosticInterval)
            }
        }
    }

    private fun stopPeriodicDiagnostics() {
        diagnosticJob?.let {
            if (it.isActive) {
                Log.d("BLEPeripheral", "Stopping periodic diagnostics")
                it.cancel()
            }
        }
        diagnosticJob = null
    }

    private fun sendConnectionEvent(eventType: String, device: BluetoothDevice) {
        if (stateCallback != null) {
            try {
                val message = JSONObject()
                message.put("type", eventType)
                message.put("address", device.address)
                message.put("name", device.name)
                
                val result = PluginResult(PluginResult.Status.OK, message)
                result.keepCallback = true
                stateCallback!!.sendPluginResult(result)
            } catch (e: JSONException) {
                Log.e("BLEPeripheral", "Error creating JSON for connection event", e)
            }
        }
    }

    private fun checkManifestPermissions() {
        try {
            val context = cordova.context
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            
            Log.d("BLEPeripheral", "=== MANIFEST PERMISSIONS ===")
            packageInfo.requestedPermissions?.forEach { permission ->
                Log.d("BLEPeripheral", "Permission: $permission")
            }
            
            val hasBluetoothScan = packageInfo.requestedPermissions?.contains(BLUETOOTH_SCAN) == true
            val hasBluetoothAdvertise = packageInfo.requestedPermissions?.contains(BLUETOOTH_ADVERTISE) == true
            val hasBluetoothConnect = packageInfo.requestedPermissions?.contains(BLUETOOTH_CONNECT) == true
            
            Log.d("BLEPeripheral", "BLUETOOTH_SCAN in manifest: $hasBluetoothScan")
            Log.d("BLEPeripheral", "BLUETOOTH_ADVERTISE in manifest: $hasBluetoothAdvertise")
            Log.d("BLEPeripheral", "BLUETOOTH_CONNECT in manifest: $hasBluetoothConnect")
            
            if (!hasBluetoothScan || !hasBluetoothAdvertise || !hasBluetoothConnect) {
                Log.e("BLEPeripheral", "!!! CRITICAL ERROR !!! Necessary permissions missing in manifest!")
            }
            
            Log.d("BLEPeripheral", "===============================")
        } catch (e: Exception) {
            Log.e("BLEPeripheral", "Error checking manifest permissions", e)
        }
    }

    private fun getSimplifiedAdvertisementData(serviceUuid: UUID): AdvertiseData {
        val builder = AdvertiseData.Builder()
        // Minimum required data
        builder.addServiceUuid(ParcelUuid(serviceUuid))
        builder.setIncludeDeviceName(false) // Sometimes device name can cause problems if it's too long
        
        Log.d("BLEPeripheral", "Created simplified advertisment data format (only service UUID)")
        return builder.build()
    }

    // Method for requesting full Bluetooth visibility
    private fun requestDiscoverableMode() {
        if (bluetoothAdapter?.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Log.d("BLEPeripheral", "Requesting full visibility mode...")
            
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            // 300 seconds (5 minutes) visibility - maximum 3600 seconds (1 hour)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            
            // Request permission without waiting for the result
            cordova.activity.startActivity(discoverableIntent)
        } else {
            Log.d("BLEPeripheral", "Bluetooth is already in full visibility mode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setProgrammaticDiscoverableMode() {
        try {
            val method = BluetoothAdapter::class.java.getMethod(
                "setScanMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            // Set visibility mode for 300 seconds
            val result = method.invoke(
                bluetoothAdapter,
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                300
            ) as? Boolean
            
            Log.d("BLEPeripheral", "Programmatic visibility mode set: ${result ?: false}")
        } catch (e: Exception) {
            Log.e("BLEPeripheral", "Error setting programmatic discoverable mode", e)
        }
    }

    // Method for creating a specific service structure
    private fun setupSpecificServiceStructure(
        serviceUUID: UUID,
        toReaderCharUUID: UUID,
        fromReaderCharUUID: UUID
    ) {
        Log.d("BLEPeripheral", "=== SPECIFIC SERVICE SETUP ===")
        Log.d("BLEPeripheral", "Service UUID: $serviceUUID")
        Log.d("BLEPeripheral", "Characteristic To Reader: $toReaderCharUUID")
        Log.d("BLEPeripheral", "Characteristic From Reader: $fromReaderCharUUID")
        
        // 1. Create the service
        val service = BluetoothGattService(
            serviceUUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // 2. Create the ToReader characteristic (Read, Notify, Indicate)
        val toReaderChar = BluetoothGattCharacteristic(
            toReaderCharUUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Add CCCD descriptor to the ToReader characteristic
        val cccdToReader = createClientCharacteristicConfigurationDescriptor(toReaderChar)
        toReaderChar.addDescriptor(cccdToReader)
        
        // 3. Create the FromReader characteristic (Write)
        val fromReaderChar = BluetoothGattCharacteristic(
            fromReaderCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // Add CCCD descriptor to the FromReader characteristic
        val cccdFromReader = createClientCharacteristicConfigurationDescriptor(fromReaderChar)
        fromReaderChar.addDescriptor(cccdFromReader)
        Log.d("BLEPeripheral", "Added descriptor with READ permission to the FromReader characteristic")
        
        // 4. Add characteristics to the service
        service.addCharacteristic(toReaderChar)
        service.addCharacteristic(fromReaderChar)
        
        // 5. Add the service to the local cache
        services[serviceUUID] = service
        
        // 6. Publish the service on the GATT server
        if (gattServer != null) {
            val success = gattServer!!.addService(service)
            Log.d("BLEPeripheral", "Adding service to GATT server: ${if (success) "SUCCESS" else "ERROR"}")
        } else {
            Log.e("BLEPeripheral", "GATT-server not initialized!")
        }
        
        Log.d("BLEPeripheral", "=== SERVICE STRUCTURE CONFIGURED ===")
        
        logServiceDescriptors(service)
    }

    private fun sendDataToReader(serviceUUID: UUID, charUUID: UUID, data: ByteArray): Boolean {
        val service = services[serviceUUID] ?: return false
        val characteristic = service.getCharacteristic(charUUID) ?: return false
        
        characteristic.value = data
        
        // Notify all connected devices about the change
        var notificationSent = false
        registeredDevices.forEach { device ->
            val notified = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            if (notified == true) {
                notificationSent = true
                Log.d("BLEPeripheral", "Notification sent to device: ${device.address}")
            }
        }
        
        return notificationSent
    }

    // Add this method for starting advertising via the modern API
    @SuppressLint("MissingPermission")
    private fun startModernAdvertising(serviceUUID: UUID): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            Log.d("BLEPeripheral", "API version < 26, using old advertisement method")
            return false
        }
        
        try {
            val bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
            
            // Create a compact advertising data package
            val advertisementData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(serviceUUID))
                .setIncludeDeviceName(false) // Remove device name to save space
                .setIncludeTxPowerLevel(false) // Remove power level to save space
                .build()
            
            Log.d("BLEPeripheral", "Created compact advertising data for service UUID: $serviceUUID")
            
            val parameters = android.bluetooth.le.AdvertisingSetParameters.Builder()
                .setLegacyMode(true) // Ensures compatibility with older scanners
                .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_HIGH)
                .setConnectable(true)
                .setScannable(true)
                .build()
            
            val callback = object : android.bluetooth.le.AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: android.bluetooth.le.AdvertisingSet?,
                    txPower: Int,
                    status: Int
                ) {
                    if (status == android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        isAdvertising = true
                        Log.d("BLEPeripheral", "Modern API advertising started successfully, txPower=$txPower")
                        advertisingStartedCallback?.success()
                    } else {
                        isAdvertising = false
                        val errorMessage = "Modern API advertising failed, code: $status"
                        Log.e("BLEPeripheral", errorMessage)
                        advertisingStartedCallback?.error(errorMessage)
                    }
                }
                
                override fun onAdvertisingSetStopped(advertisingSet: android.bluetooth.le.AdvertisingSet?) {
                    super.onAdvertisingSetStopped(advertisingSet)
                    isAdvertising = false
                    Log.d("BLEPeripheral", "Modern API advertising stopped")
                }
            }
            
            bluetoothLeAdvertiser.startAdvertisingSet(
                parameters,
                advertisementData,
                null, // scanResponse
                null, // periodicParameters 
                null, // periodicData
                callback
            )
            
            Log.d("BLEPeripheral", "Requested modern API advertising start")
            return true
        } catch (e: Exception) {
            Log.e("BLEPeripheral", "Modern API advertising failed", e)
            return false
        }
    }

    // Method for checking and logging service descriptors
    private fun logServiceDescriptors(service: BluetoothGattService) {
        Log.d("BLEPeripheral", "=== SERVICE: ${service.uuid} ===")
        
        service.characteristics.forEach { char ->
            Log.d("BLEPeripheral", "  Characteristic: ${char.uuid}")
            Log.d("BLEPeripheral", "    Properties: ${formatCharacteristicProperties(char.properties)}")
            Log.d("BLEPeripheral", "    Permissions: ${formatCharacteristicPermissions(char.permissions)}")
            
            if (char.descriptors.isEmpty()) {
                Log.d("BLEPeripheral", "    !!! WARNING: Characteristic has no descriptors !!!")
            } else {
                char.descriptors.forEach { desc ->
                    Log.d("BLEPeripheral", "    Descriptor: ${desc.uuid}")
                    Log.d("BLEPeripheral", "      Permissions: ${formatDescriptorPermissions(desc.permissions)}")
                    
                    // Special handling for CCCD
                    if (desc.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                        Log.d("BLEPeripheral", "      CCCD value: ${interpretCCCDValue(desc)}")
                    } else if (desc.value != null) {
                        Log.d("BLEPeripheral", "      Value: ${desc.value.joinToString(" ") { "%02X".format(it) }}")
                    }
                }
            }
        }
        
        Log.d("BLEPeripheral", "==============================")
    }

    // Helper method for formatting characteristic properties
    private fun formatCharacteristicProperties(properties: Int): String {
        val props = mutableListOf<String>()
        if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.add("READ")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.add("WRITE")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) props.add("WRITE_NO_RESPONSE")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.add("NOTIFY")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.add("INDICATE")
        
        return "${properties} (${props.joinToString(", ")})"
    }

    // Helper method for formatting characteristic permissions
    private fun formatCharacteristicPermissions(permissions: Int): String {
        val perms = mutableListOf<String>()
        if ((permissions and BluetoothGattCharacteristic.PERMISSION_READ) != 0) perms.add("READ")
        if ((permissions and BluetoothGattCharacteristic.PERMISSION_WRITE) != 0) perms.add("WRITE")
        
        return "${permissions} (${perms.joinToString(", ")})"
    }

    // Helper method for formatting descriptor permissions
    private fun formatDescriptorPermissions(permissions: Int): String {
        val perms = mutableListOf<String>()
        if ((permissions and BluetoothGattDescriptor.PERMISSION_READ) != 0) perms.add("READ")
        if ((permissions and BluetoothGattDescriptor.PERMISSION_WRITE) != 0) perms.add("WRITE")
        
        return "${permissions} (${perms.joinToString(", ")})"
    }

    // Method for correct interpretation of CCCD values
    private fun interpretCCCDValue(descriptor: BluetoothGattDescriptor): String {

        val value = descriptor.value
        if (value == null || value.isEmpty()) {
            return "Not set (null/empty)"
        }
        
        // CCCD values: 
        // 0x00 00: Notifications and indications disabled
        // 0x01 00: Notifications enabled
        // 0x02 00: Indications enabled
        // 0x03 00: Notifications and indications enabled

        return when {
            Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> 
                "Notifications and indications disabled (0x0000)"
            Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> 
                "Notifications enabled (0x0001)"
            Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> 
                "Indications enabled (0x0002)"
            value.size >= 2 && value[0].toInt() == 3 && value[1].toInt() == 0 -> 
                "Notifications and indications enabled (0x0003)"
            else -> "Non-standard value: ${value.joinToString(" ") { "%02X".format(it) }}"
        }
    }

    // More detailed advertising diagnostics
    private fun logAdvertisingDetails(serviceUUID: UUID) {
        Log.d("BLEPeripheral", "====== ADVERTISING DIAGNOSTICS ======")
        Log.d("BLEPeripheral", "Active: $isAdvertising")
        Log.d("BLEPeripheral", "Advertised service UUID: $serviceUUID")
        Log.d("BLEPeripheral", "Device name in advertisement: ${bluetoothAdapter?.name}")
        Log.d("BLEPeripheral", "MAC address: ${bluetoothAdapter?.address}")
        Log.d("BLEPeripheral", "Advertisement support: ${bluetoothAdapter?.isMultipleAdvertisementSupported}")
        
        // Check visibility mode
        when (bluetoothAdapter?.scanMode) {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> 
                Log.d("BLEPeripheral", "Visibility mode: FULLY VISIBLE (Discoverable)")
            BluetoothAdapter.SCAN_MODE_CONNECTABLE -> 
                Log.d("BLEPeripheral", "Visibility mode: CONNECTION ONLY (not discoverable)")
            BluetoothAdapter.SCAN_MODE_NONE -> 
                Log.d("BLEPeripheral", "Visibility mode: INVISIBLE")
            else -> 
                Log.d("BLEPeripheral", "Visibility mode: UNKNOWN")
        }
        
        // Check Nearby Connections
        try {
            val nearbyClass = Class.forName("com.google.android.gms.nearby.Nearby")
            Log.d("BLEPeripheral", "Google Nearby API detected in application")
        } catch (e: ClassNotFoundException) {
            Log.d("BLEPeripheral", "Google Nearby API not used")
        }
        
        Log.d("BLEPeripheral", "==================================")
    }

    // Method for forcing the visibility mode
    private fun forceDiscoverableMode() {
        try {
            // Programmatically set the visibility mode
            if (bluetoothAdapter?.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                Log.d("BLEPeripheral", "Request full visibility mode (DISCOVERABLE)")
                
                // Method 1: Request system dialog to enable visibility
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
                cordova.activity.startActivity(discoverableIntent)
                
                // Wait a little bit to allow the user to respond to the dialog
                Thread.sleep(1000)
                
                Log.d("BLEPeripheral", "New scanning mode: ${getScanModeString(bluetoothAdapter?.scanMode)}")
            } else {
                Log.d("BLEPeripheral", "Device already in full visibility mode")
            }
        } catch (e: Exception) {
            Log.e("BLEPeripheral", "Error setting visibility mode", e)
        }
    }

    // Method for starting legacy advertising with more reliable parameters
    private fun startLegacyAdvertising(serviceUUID: UUID): Boolean {
        try {
            val bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
                ?: return false
            
            // Create the smallest possible advertising data
            val simpleData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(serviceUUID))
                .setIncludeDeviceName(false) // Too long for legacy advertising
                .setIncludeTxPowerLevel(false) // Too long for legacy advertising
                .build()
            
            Log.d("BLEPeripheral", "Created minimal advertisement data with UUID: $serviceUUID")
            
            // Settings with maximum power and low delay
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            
            // Local callback for tracking the result
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    isAdvertising = true
                    Log.d("BLEPeripheral", "!!! LEGACY ADVERTISEMENT SUCCESSFULLY STARTED !!!")
                    Log.d("BLEPeripheral", "Mode=${settingsInEffect.mode}, power=${settingsInEffect.txPowerLevel}")
                }
                
                override fun onStartFailure(errorCode: Int) {
                    isAdvertising = false
                    Log.e("BLEPeripheral", "!!! ERROR STARTING LEGACY ADVERTISEMENT: ${getAdvertiseErrorMessage(errorCode)} !!!")
                }
            }
            
            // Start advertising
            bluetoothLeAdvertiser.startAdvertising(settings, simpleData, callback)
            Log.d("BLEPeripheral", "Request to start legacy advertisement sent")
            return true
        } catch (e: Exception) {
            Log.e("BLEPeripheral", "Exception when starting legacy advertisement", e)
            return false
        }
    }

    // Additional method for interpreting advertising error codes
    private fun getAdvertiseErrorMessage(errorCode: Int): String {
        return when(errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> 
                "Advertisement already started (code $errorCode)"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> 
                "Advertisement data too large (code $errorCode)"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> 
                "Feature not supported (code $errorCode)"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> 
                "Internal error (code $errorCode)"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> 
                "Too many advertisers (code $errorCode)"
            else -> "Unknown error (code $errorCode)"
        }
    }

    companion object {
        // actions
        private const val CREATE_SERVICE = "createService"
        private const val CREATE_SERVICE_FROM_JSON = "createServiceFromJSON"
        private const val REMOVE_SERVICE = "removeService"
        private const val REMOVE_ALL_SERVICES = "removeAllServices"
        private const val ADD_CHARACTERISTIC = "addCharacteristic"
        private const val PUBLISH_SERVICE = "publishService"
        private const val START_ADVERTISING = "startAdvertising"
        private const val STOP_ADVERTISING = "stopAdvertising"
        private const val SET_CHARACTERISTIC_VALUE = "setCharacteristicValue"
        private const val SET_CHARACTERISTIC_VALUE_CHANGED_LISTENER =
            "setCharacteristicValueChangedListener"
        private const val SET_CHARACTERISTIC_VALUE_REQUESTED_LISTENER =
            "setCharacteristicValueRequestedListener"
        private const val RECEIVE_REQUESTED_CHARACTERISTIC_VALUE =
            "receiveRequestedCharacteristicValue"
        private const val GET_BLUETOOTH_SYSTEM_STATE = "getBluetoothSystemState"
        private const val START_SENDING_STASHED_READ_WRITE_EVENTS = "startSendingStashedReadWriteEvents"
        private const val SETUP_READER_COMMUNICATION = "setupReaderCommunication"

        // 0x2902 https://www.bluetooth.com/specifications/gatt/descriptors
        private val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // private static final String START_STATE_NOTIFICATIONS = "startStateNotifications";
        // private static final String STOP_STATE_NOTIFICATIONS = "stopStateNotifications";
        private const val SET_BLUETOOTH_STATE_CHANGED_LISTENER = "setBluetoothStateChangedListener"
        private const val SETTINGS = "showBluetoothSettings"
        private const val ENABLE = "enable"
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
        private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        private const val REQUEST_BLUETOOTH_CONNECT = 42
        private const val REQUEST_BLUETOOTH_ADVERTISE = 43
        private const val REQUEST_BLUETOOTH_SCAN = 44
        private var COMPILE_SDK_VERSION = -1
        private const val TAG = "BLEPeripheral"
        private const val REQUEST_ENABLE_BLUETOOTH = 17
    }
}