package im.zego.live.service;

import android.util.Log;

import com.google.gson.Gson;

import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

import im.zego.live.ZegoRoomManager;
import im.zego.live.ZegoZIMManager;
import im.zego.live.callback.ZegoRoomCallback;
import im.zego.live.constants.ZegoRoomConstants;
import im.zego.live.helper.ZegoRoomAttributesHelper;
import im.zego.live.listener.ZegoRoomServiceListener;
import im.zego.live.model.OperationAction;
import im.zego.live.model.OperationCommand;
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

/**
 * Created by rocket_wang on 2021/12/14.
 */
public class ZegoRoomService {

    private ZegoRoomServiceListener listener;
    // room info object
    public ZegoRoomInfo roomInfo = new ZegoRoomInfo();
    public OperationCommand operation = new OperationCommand();

    private static final String TAG = "ZegoRoomService";

    // create a room
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

    // join a room
    public void joinRoom(String roomID, final String token, final ZegoRoomCallback callback) {
        ZegoUserInfo localUserInfo = ZegoRoomManager.getInstance().userService.localUserInfo;
        localUserInfo.setRole(ZegoRoomUserRole.Participant);

        ZegoZIMManager.getInstance().zim.joinRoom(roomID, (roomInfo, errorInfo) -> {
            if (errorInfo.code == ZIMErrorCode.SUCCESS) {
                loginRTCRoom(roomID, token, localUserInfo);
                this.roomInfo.setRoomID(roomInfo.baseInfo.roomID);
                this.roomInfo.setRoomName(roomInfo.baseInfo.roomName);
            }
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
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

    // leave the room
    public void leaveRoom(final ZegoRoomCallback callback) {
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
    }

    public void setListener(ZegoRoomServiceListener listener) {
        this.listener = listener;
    }

    /**
     * @param zim
     * @param info
     * @param roomID
     */
    public void onRoomAttributesUpdated(ZIM zim, ZIMRoomAttributesUpdateInfo info, String roomID) {
        Log.d(TAG,
                "onRoomAttributesUpdated() called with: info.action = [" + info.action + "], info.roomAttributes = ["
                        + info.roomAttributes + "], roomID = [" + roomID
                        + "]");
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
            if (!operation.isSeqValid(action.getSeq())) {
                resendRoomAttributes(info.roomAttributes, action);
                return;
            } else {
                operation.setAction(action);
            }
        }

        // update seat list & requestCoHostList
        operation.update(info.roomAttributes);

        List<ZegoUserInfo> userInfoList = ZegoRoomManager.getInstance().userService.getUserList();
        for (ZegoUserInfo zegoUserInfo : userInfoList) {
            zegoUserInfo.setHasRequestedCoHost(operation.getRequestCoHostList().contains(zegoUserInfo.getUserID()));
        }

        ZegoUserService userService = ZegoRoomManager.getInstance().userService;
        if (userService != null) {
            userService.onRoomAttributesUpdated(info.roomAttributes, operation);
        }
    }

    private void resendRoomAttributes(HashMap<String, String> roomAttributes, OperationAction action) {
        // only the host can resent the room attributes
        if (!ZegoRoomManager.getInstance().userService.isSelfHost()) return;

    }

    public void onConnectionStateChanged(ZIM zim, ZIMConnectionState state, ZIMConnectionEvent event,
                                         JSONObject extendedData) {
        if (listener != null) {
            listener.onConnectionStateChanged(state, event);
        }
    }

    public void onRoomStreamUpdate(String roomID, ZegoUpdateType updateType, List<ZegoStream> streamList) {
        if (listener != null) {
            listener.onRoomStreamUpdate(roomID, updateType, streamList);
        }
//        for (ZegoStream zegoStream : streamList) {
//            if (updateType == ZegoUpdateType.ADD) {
//                ZegoExpressEngine.getEngine().startPlayingStream(zegoStream.streamID, null);
//            } else {
//                ZegoExpressEngine.getEngine().stopPlayingStream(zegoStream.streamID);
//            }
//        }
    }
}