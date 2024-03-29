package im.zego.live.service;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;

import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import im.zego.live.ZegoRoomManager;
import im.zego.live.ZegoZIMManager;
import im.zego.live.callback.ZegoRoomCallback;
import im.zego.live.constants.ZegoRoomConstants;
import im.zego.live.helper.UserInfoHelper;
import im.zego.live.helper.ZegoRoomAttributesHelper;
import im.zego.live.listener.ZegoRoomServiceListener;
import im.zego.live.model.OperationAction;
import im.zego.live.model.OperationActionType;
import im.zego.live.model.OperationCommand;
import im.zego.live.model.ZegoCoHostSeatModel;
import im.zego.live.model.ZegoRoomInfo;
import im.zego.live.model.ZegoRoomUserRole;
import im.zego.live.model.ZegoUserInfo;
import im.zego.zegoexpress.ZegoExpressEngine;
import im.zego.zegoexpress.constants.ZegoUpdateType;
import im.zego.zegoexpress.entity.ZegoRoomConfig;
import im.zego.zegoexpress.entity.ZegoStream;
import im.zego.zegoexpress.entity.ZegoUser;
import im.zego.zim.ZIM;
import im.zego.zim.entity.ZIMRoomAdvancedConfig;
import im.zego.zim.entity.ZIMRoomAttributesUpdateInfo;
import im.zego.zim.entity.ZIMRoomInfo;
import im.zego.zim.enums.ZIMConnectionEvent;
import im.zego.zim.enums.ZIMConnectionState;
import im.zego.zim.enums.ZIMErrorCode;
import im.zego.zim.enums.ZIMRoomAttributesUpdateAction;
import im.zego.zim.enums.ZIMRoomEvent;
import im.zego.zim.enums.ZIMRoomState;

/**
 * Class ZEGOLive information management
 * <p>
 * Description: This class contains the room information management logic, such as the logic of create a room, join a room, leave a room, disable the text chat in room, etc.
 */
public class ZegoRoomService {

    // The listener related to the room status
    private ZegoRoomServiceListener listener;

    // Room information, it will be assigned after join the room successfully.
    // And it will be updated synchronously when the room status updates.
    public ZegoRoomInfo roomInfo = new ZegoRoomInfo();

    public OperationCommand operation = new OperationCommand();

    private static final String TAG = "ZegoRoomService";

    /**
     * Create a room
     * <p>
     * Description: This method can be used to create a room. The room creator will be the Host by default when the room is created successfully.
     * <p>
     * Call this method at: After user logs in
     *
     * @param roomID   refers to the room ID, the unique identifier of the room. This is required to join a room and cannot be null.
     * @param roomName refers to the room name. This is used for display in the room and cannot be null.
     * @param token    refers to the authentication token. To get this, see the documentation: https://docs.zegocloud.com/article/11648
     * @param callback refers to the callback for create a room.
     */
    public void createRoom(String roomID, String roomName, final String token,
                           final ZegoRoomCallback callback) {
        ZegoUserInfo localUserInfo = ZegoRoomManager.getInstance().userService.localUserInfo;
        localUserInfo.setRole(ZegoRoomUserRole.Host);

        roomInfo.setRoomID(roomID);
        roomInfo.setRoomName(roomName);
        roomInfo.setHostID(localUserInfo.getUserID());

        ZIMRoomInfo zimRoomInfo = new ZIMRoomInfo();
        zimRoomInfo.roomID = roomID;
        zimRoomInfo.roomName = roomName;

        HashMap<String, String> roomAttributes = new HashMap<>();
        roomAttributes.put(ZegoRoomConstants.KEY_ROOM_INFO, ZegoRoomAttributesHelper.gson.toJson(roomInfo));
        ZIMRoomAdvancedConfig config = new ZIMRoomAdvancedConfig();
        config.roomAttributes = roomAttributes;

        ZegoZIMManager.getInstance().zim.createRoom(zimRoomInfo, config, (roomInfo, errorInfo) -> {
            if (errorInfo.code == ZIMErrorCode.SUCCESS) {
                loginRTCRoom(roomID, token, localUserInfo);
            }
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
            }
        });
    }

    /**
     * Join a room
     * <p>
     * Description: This method can be used to join a room, the room must be an existing room.
     * <p>
     * Call this method at: After user logs in
     *
     * @param roomID   refers to the ID of the room you want to join, and cannot be null.
     * @param token    refers to the authentication token. To get this, see the documentation: https://docs.zegocloud.com/article/11648
     * @param callback refers to the callback for join a room.
     */
    public void joinRoom(String roomID, final String token, final ZegoRoomCallback callback) {
        ZegoUserInfo localUserInfo = ZegoRoomManager.getInstance().userService.localUserInfo;
        localUserInfo.setRole(ZegoRoomUserRole.Participant);

        ZegoZIMManager.getInstance().zim.joinRoom(roomID, (roomInfo, errorInfo) -> {
            if (errorInfo.code == ZIMErrorCode.SUCCESS) {
                loginRTCRoom(roomID, token, localUserInfo);
                this.roomInfo.setRoomID(roomInfo.baseInfo.roomID);
                this.roomInfo.setRoomName(roomInfo.baseInfo.roomName);
                ZegoZIMManager.getInstance().zim.queryRoomAllAttributes(roomID, (roomAttributes, errorInfo2) -> {
                    Set<String> keys = roomAttributes.keySet();
                    for (String key : keys) {
                        if (key.equals(ZegoRoomConstants.KEY_ROOM_INFO)) {
                            this.roomInfo = new Gson().fromJson(roomAttributes.get(key), ZegoRoomInfo.class);
                        }
                    }

                    if (callback != null) {
                        callback.onRoomCallback(errorInfo2.code.value());
                    }
                });
            } else {
                if (callback != null) {
                    callback.onRoomCallback(errorInfo.code.value());
                }
            }
        });
    }

    private void loginRTCRoom(String roomID, String token, ZegoUserInfo localUserInfo) {
        ZegoUser user = new ZegoUser(localUserInfo.getUserID(), localUserInfo.getUserName());
        ZegoRoomConfig roomConfig = new ZegoRoomConfig();
        roomConfig.token = token;
        ZegoExpressEngine.getEngine().loginRoom(roomID, user, roomConfig);
        ZegoExpressEngine.getEngine().startSoundLevelMonitor(1000);
    }

    /**
     * Leave the room
     * <p>
     * Description: This method can be used to leave the room you joined. The room will be ended when the Host leaves, and all users in the room will be forced to leave the room.
     * <p>
     * Call this method at: After joining a room
     *
     * @param callback refers to the callback for leave a room.
     */
    public void leaveRoom(final ZegoRoomCallback callback) {
        if (UserInfoHelper.isSelfInRequestedCoHost()) {
            ZegoRoomManager.getInstance().userService.cancelRequestToCoHost(errorCode -> {
            });
        }
        if (UserInfoHelper.isSelfCoHost()) {
            ZegoRoomManager.getInstance().userService.leaveSeat(null, errorCode -> {
            });
        }

        ZegoMessageService messageService = ZegoRoomManager.getInstance().messageService;
        if (messageService != null) {
            messageService.reset();
        }
        ZegoUserService userService = ZegoRoomManager.getInstance().userService;
        if (userService != null) {
            userService.leaveRoom();
        }
        reset();

        ZegoExpressEngine.getEngine().stopSoundLevelMonitor();
        ZegoExpressEngine.getEngine().stopPublishingStream();

        ZegoExpressEngine.getEngine().logoutRoom(roomInfo.getRoomID());

        ZegoZIMManager.getInstance().zim.leaveRoom(roomInfo.getRoomID(), errorInfo -> {
            Log.d(TAG, "leaveRoom() called with: errorInfo = [" + errorInfo.code + "]" + errorInfo.message);
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
            }
        });
    }

    void reset() {
        roomInfo.setRoomName("");
        roomInfo.setHostID("");
        operation = new OperationCommand();
    }

    public void setListener(ZegoRoomServiceListener listener) {
        this.listener = listener;
    }

    public void onRoomStateChanged(ZIM zim, ZIMRoomState state, ZIMRoomEvent event, JSONObject extendedData,
                                   String roomID) {
        Log.d(TAG, "onRoomStateChanged() called with: zim = [" + zim + "], state = [" + state + "], event = [" + event
                + "], extendedData = [" + extendedData + "], roomID = [" + roomID + "]");
        if (state == ZIMRoomState.CONNECTED) {
            boolean newInRoom = StringUtils.isEmpty(this.roomInfo.getHostID());
            if (!newInRoom && !TextUtils.isEmpty(roomID)) {
                ZegoZIMManager.getInstance().zim.queryRoomAllAttributes(roomID, (roomAttributes, errorInfo) -> {
                    boolean hostLeft = errorInfo.getCode() == ZIMErrorCode.SUCCESS
                            && !roomAttributes.keySet().contains(ZegoRoomConstants.KEY_ROOM_INFO);
                    boolean roomNotExisted = errorInfo.getCode() == ZIMErrorCode.ROOM_NOT_EXIST;
                    if (hostLeft || roomNotExisted) {
                        if (listener != null) {
                            listener.onReceiveRoomInfoUpdate(null);
                        }
                    } else {
                        String userID = ZegoRoomManager.getInstance().userService.localUserInfo.getUserID();
                        ZegoRoomListService.heartBeat(userID, roomID, false, null);
                    }
                });
            }
        } else if (state == ZIMRoomState.DISCONNECTED) {
            if (listener != null) {
                listener.onReceiveRoomInfoUpdate(null);
            }
        }
    }

    public void onRoomAttributesUpdated(ZIM zim, ZIMRoomAttributesUpdateInfo info, String roomID) {
        Log.d(TAG,
                "onRoomAttributesUpdated() called with: info.action = [" + info.action + "], info.roomAttributes = ["
                        + Collections.singletonList(info.roomAttributes) + "], roomID = [" + roomID
                        + "]");
        if (info.action == ZIMRoomAttributesUpdateAction.DELETE) {
            String roomJson = info.roomAttributes.get(ZegoRoomConstants.KEY_ROOM_INFO);
            if (TextUtils.isEmpty(roomJson)) {
                if (listener != null) {
                    listener.onReceiveRoomInfoUpdate(null);
                }
                return;
            }
        }

        Gson gson = ZegoRoomAttributesHelper.gson;
        String roomJson = info.roomAttributes.get(ZegoRoomConstants.KEY_ROOM_INFO);
        if (StringUtils.isNotEmpty(roomJson)) {
            ZegoRoomInfo roomInfo = gson.fromJson(roomJson, ZegoRoomInfo.class);
            this.roomInfo = roomInfo;
            if (listener != null) {
                listener.onReceiveRoomInfoUpdate(roomInfo);
            }
        }

        String actionJson = info.roomAttributes.get(ZegoRoomConstants.KEY_ACTION);
        if (StringUtils.isNotEmpty(actionJson)) {
            OperationAction action = gson.fromJson(actionJson, OperationAction.class);
            // if the seq is invalid
            // only the host can resent the room attributes
            if (!operation.isSeqValid(action.getSeq())) {
                if (UserInfoHelper.isSelfHost()) {
                    resendRoomAttributes(info.roomAttributes, action);
                }
                return;
            } else {
                operation.setAction(action);
            }

            // update seat list & requestCoHostList
            operation.update(info.roomAttributes);

            ZegoRoomManager.getInstance().userService.coHostList = operation.getSeatList();

            List<ZegoUserInfo> userInfoList = ZegoRoomManager.getInstance().userService.getUserList();
            for (ZegoUserInfo zegoUserInfo : userInfoList) {
                zegoUserInfo.setHasRequestedCoHost(operation.getRequestCoHostList().contains(zegoUserInfo.getUserID()));

                if (UserInfoHelper.isUserIDCoHost(zegoUserInfo.getUserID())) {
                    zegoUserInfo.setHasInvited(false);
                }
            }

            // if coHost not in user list, this member may disconnect or already leave room
            // we need make him leave seat actively
            // remember only room host have this operation rights
            if (UserInfoHelper.isSelfHost()) {
                List<ZegoCoHostSeatModel> coHostList = ZegoRoomManager.getInstance().userService.coHostList;
                for (ZegoCoHostSeatModel model : coHostList) {
                    boolean isFound = false;
                    for (ZegoUserInfo zegoUserInfo : userInfoList) {
                        if (Objects.equals(model.getUserID(), zegoUserInfo.getUserID()) && StringUtils.isNotEmpty(zegoUserInfo.getUserID())) {
                            isFound = true;
                            break;
                        }
                    }

                    if (!isFound) {
                        ZegoRoomManager.getInstance().userService.leaveSeat(model.getUserID(), errorCode -> {

                        });
                    }
                }
            }

            if (!UserInfoHelper.isSelfCoHost()) {
                ZegoExpressEngine.getEngine().stopPublishingStream();
            }

            switch (operation.getAction().getType()) {
                case Mic:
                case Camera:
                case Mute:
                case TakeSeat:
                    if (listener != null) {
                        listener.onReceiveCoHostListUpdate(operation.getAction());
                    }
                    break;
                case LeaveSeat:
                    if (!UserInfoHelper.isSelfCoHost()) {
                        ZegoExpressEngine.getEngine().stopPublishingStream();
                    }
                    if (listener != null) {
                        listener.onReceiveCoHostListUpdate(operation.getAction());
                    }
                    break;
            }

            ZegoUserService userService = ZegoRoomManager.getInstance().userService;
            if (userService != null) {
                userService.onRoomAttributesUpdated(info.roomAttributes, operation);
            }
        }
    }

    private void resendRoomAttributes(HashMap<String, String> roomAttributes, OperationAction action) {
        String roomID = this.roomInfo.getRoomID();

        OperationCommand operation = this.operation.copy();
        operation.setAction(action);
        operation.addSeq(action.getSeq());

        operation.updateForResend(roomAttributes);

        HashMap<String, String> map;
        if (operation.getAction().getType() == OperationActionType.Mic
                || operation.getAction().getType() == OperationActionType.Camera
                || operation.getAction().getType() == OperationActionType.Mute
                || operation.getAction().getType() == OperationActionType.TakeSeat
                || operation.getAction().getType() == OperationActionType.LeaveSeat
        ) {
            map = operation.getAttributes(OperationCommand.OperationAttributeTypeSeat);
        } else {
            map = operation.getAttributes(OperationCommand.OperationAttributeTypeRequestCoHost);
        }

        Log.d(TAG, "resendRoomAttributes() called with: roomAttributes = [" + Collections.singletonList(map) + "]");

        ZegoRoomAttributesHelper.setRoomAttributes(map, roomID, ZegoRoomAttributesHelper.getAttributesSetConfig(), errorCode -> {
        });
    }

    public void onConnectionStateChanged(ZIM zim, ZIMConnectionState state, ZIMConnectionEvent event,
                                         JSONObject extendedData) {
        if (listener != null) {
            listener.onConnectionStateChanged(state, event);
        }
    }

    public void onRoomStreamUpdate(String roomID, ZegoUpdateType updateType, List<ZegoStream> streamList) {
        for (ZegoStream zegoStream : streamList) {
            if (updateType == ZegoUpdateType.DELETE) {
                ZegoDeviceService deviceService = ZegoRoomManager.getInstance().deviceService;
                deviceService.stopPlayStream(zegoStream.user.userID);
            }
        }
    }
}