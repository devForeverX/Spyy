package com.remote.androidcontroller

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.remote.androidcontroller.ui.screens.AdminRemoteScreen
import com.remote.androidcontroller.ui.screens.AudioStreamScreen
import com.remote.androidcontroller.ui.screens.ExtrasScreen
import com.remote.androidcontroller.ui.screens.MainModeScreen
import com.remote.androidcontroller.ui.screens.PairingScreen
import com.remote.androidcontroller.ui.screens.PermissionsScreen
import com.remote.androidcontroller.ui.screens.VirtualKeyboardScreen
import com.remote.androidcontroller.ui.theme.AndroidRemoteControlTheme
import com.remote.androidcontroller.network.RemoteTouchAction
import com.remote.androidcontroller.viewmodel.AppRole
import com.remote.androidcontroller.viewmodel.ConnectionStatus
import com.remote.androidcontroller.viewmodel.RemoteControlViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val consentData = result.data
            if (result.resultCode == Activity.RESULT_OK && consentData != null) {
                viewModel.startRemoteScreenCapture(result.resultCode, consentData)
            } else {
                viewModel.onScreenCaptureDenied()
            }
        }
        setContent {
            AndroidRemoteControlTheme {
                val uiState by viewModel.uiState.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when {
                        // 1. Role not selected yet
                        uiState.role == AppRole.UNSELECTED -> {
                            MainModeScreen(
                                serverUrl = uiState.serverUrl,
                                errorMessage = uiState.lastActionResultMessage,
                                onServerUrlChange = { viewModel.setServerUrl(it) },
                                onSelectAdminMode = { viewModel.selectRole(AppRole.ADMIN) },
                                onSelectRemoteMode = { viewModel.selectRole(AppRole.REMOTE) }
                            )
                        }

                        // 2. Not paired yet or verifying SAS -> Show Pairing Screen
                        !uiState.isPaired || uiState.sasVerificationCode != null -> {
                            PairingScreen(
                                isAdmin = uiState.role == AppRole.ADMIN,
                                generatedCode = uiState.currentPairingCode,
                                sasVerificationCode = uiState.sasVerificationCode,
                                isPairingCompleted = uiState.isPaired,
                                isLockedOut = uiState.isLockedOut,
                                lockoutRemainingSeconds = uiState.lockoutRemainingSeconds,
                                isConnecting = uiState.isPairingHandshakeInProgress ||
                                    uiState.connectionStatus == ConnectionStatus.CONNECTING_SIGNALING ||
                                    uiState.connectionStatus == ConnectionStatus.PAIRED_CONNECTING_PEER,
                                statusMessage = uiState.lastActionResultMessage,
                                onGenerateNewCode = { viewModel.generateNewPairingCode() },
                                onSubmitEnteredCode = { viewModel.submitPairingCode(it) },
                                onConfirmSasCode = {
                                    if (uiState.role == AppRole.REMOTE) {
                                        viewModel.confirmTargetReady()
                                    } else {
                                        viewModel.confirmSasCode()
                                    }
                                },
                                onRevokePairing = { viewModel.revokePairing() }
                            )
                        }

                        // 3. Paired & in Admin Mode -> Show Central Smartphone Remote Screen
                        uiState.role == AppRole.ADMIN -> {
                            val isConnected = uiState.isControlChannelOpen

                            AdminRemoteScreen(
                                isConnected = isConnected,
                                deviceStatus = uiState.deviceStatus,
                                remoteVideoTrack = uiState.remoteVideoTrack,
                                eglContext = viewModel.currentEglContext(),
                                remoteScreenShareActive = uiState.remoteScreenShareActive,
                                remoteScreenWidth = uiState.remoteScreenWidth,
                                remoteScreenHeight = uiState.remoteScreenHeight,
                                onRemoteTap = { x, y -> viewModel.sendRemoteTouch(RemoteTouchAction.TAP, x, y) },
                                onRemoteSwipe = { x, y, endX, endY, durationMs ->
                                    viewModel.sendRemoteTouch(RemoteTouchAction.SWIPE, x, y, endX, endY, durationMs)
                                },
                                measuredRttMs = uiState.measuredRttMs,
                                lastActionResultMessage = uiState.lastActionResultMessage,
                                onNavigationAction = { viewModel.sendNavigationAction(it) },
                                onAdjustVolume = { viewModel.adjustVolume(it) },
                                onDisconnect = { viewModel.revokePairing() },
                                keyboardContent = {
                                    VirtualKeyboardScreen(
                                        enabled = isConnected,
                                        onTextInput = { text, isEnter, isBackspace ->
                                            viewModel.sendTextInput(text, isEnter, isBackspace)
                                        }
                                    )
                                },
                                audioContent = {
                                    AudioStreamScreen(
                                        enabled = isConnected,
                                        audioStatus = uiState.audioStatus,
                                        onSelectAudioFile = { viewModel.selectAudioFile(it) },
                                        onPlay = { viewModel.playSelectedAudioFile() },
                                        onPause = { viewModel.pauseAudio() },
                                        onResume = { viewModel.resumeAudio() },
                                        onStop = { viewModel.stopAudio() },
                                        onVolumeChange = { viewModel.setAudioVolume(it) },
                                        onPlayTestTone = { viewModel.playLocatorChimeOnRemote() }
                                    )
                                },
                                extrasContent = {
                                    ExtrasScreen(
                                        enabled = isConnected,
                                        deviceStatus = uiState.deviceStatus,
                                        measuredRttMs = uiState.measuredRttMs,
                                        onSetBrightness = { viewModel.setBrightness(it) },
                                        onSetFlashlight = { viewModel.setFlashlight(it) },
                                        onTriggerVibration = { viewModel.triggerVibration(it) },
                                        onTransferClipboard = { viewModel.transferClipboard(it) },
                                        onNavigationAction = { viewModel.sendNavigationAction(it) }
                                    )
                                },
                                permissionsContent = {
                                    PermissionsScreen(
                                        permissionStatus = uiState.permissionStatus,
                                        isRemoteDevice = false
                                    )
                                }
                            )
                        }

                        // 4. Remote Device Host Mode
                        else -> {
                            PermissionsScreen(
                                permissionStatus = uiState.permissionStatus,
                                isRemoteDevice = true,
                                pairedDeviceName = uiState.pairedDeviceName,
                                connectionStatus = uiState.connectionStatus,
                                onRevokePairing = { viewModel.revokePairing() },
                                screenShareActive = uiState.isScreenSharing,
                                screenSharePending = uiState.isScreenSharePending,
                                onStartScreenShare = {
                                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                                },
                                onStopScreenShare = { viewModel.stopRemoteScreenCapture() }
                            )
                        }
                    }
                }
            }
        }
    }
}
