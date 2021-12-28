package im.zego.live.service;

import android.util.Log;

import androidx.annotation.NonNull;

import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import im.zego.live.ZegoRoomManager;
import im.zego.live.ZegoZIMManager;
import im.zego.live.callback.ZegoOnlineRoomUserListCallback;
import im.zego.live.callback.ZegoOnlineRoomUsersNumCallback;
import im.zego.live.callback.ZegoRoomCallback;
import im.zego.live.constants.ZegoRoomErrorCode;
import im.zego.live.helper.ZegoRoomAttributesHelper;
import im.zego.live.listener.ZegoUserServiceListener;
import im.zego.live.model.OperationAction;
import im.zego.live.model.OperationActionType;
import im.zego.live.model.OperationCommand;
import im.zego.live.model.ZegoCoHostSeatModel;
import im.zego.live.model.ZegoCustomCommand;
import im.zego.live.model.ZegoRoomInfo;
import im.zego.live.model.ZegoRoomUserRole;
import im.zego.live.model.ZegoUserInfo;
import im.zego.live.util.Triple;
import im.zego.zegoexpress.ZegoExpressEngine;
import im.zego.zim.ZIM;
import im.zego.zim.callback.ZIMMemberQueriedCallback;
import im.zego.zim.entity.ZIMCustomMessage;
import im.zego.zim.entity.ZIMError;
import im.zego.zim.entity.ZIMMessage;
import im.zego.zim.entity.ZIMQueryMemberConfig;
import im.zego.zim.entity.ZIMRoomAttributesSetConfig;
import im.zego.zim.entity.ZIMUserInfo;
import im.zego.zim.enums.ZIMErrorCode;
import im.zego.zim.enums.ZIMMessageType;

/**
 * Created by rocket_wang on 2021/12/14.
 */
public class ZegoUserService {

    private static final String TAG = "ZegoUserService";

    public ZegoUserInfo localUserInfo;
    private ZegoUserServiceListener listener;
    // local login user info
    // room member list
    private final List<ZegoUserInfo> userList = new ArrayList<>();
    private final List<ZegoCoHostSeatModel> coHostList = new ArrayList<>();
    private final Map<String, ZegoUserInfo> userMap = new HashMap<>();

    public boolean isSelfHost() {
        String hostID = ZegoRoomManager.getInstance().roomService.roomInfo.getHostID();
        String userID = localUserInfo.getUserID();
        return Objects.equals(hostID, userID) && StringUtils.isNotEmpty(hostID);
    }

    // user login
    public void login(ZegoUserInfo userInfo, String token, final ZegoRoomCallback callback) {
        ZIMUserInfo zimUserInfo = new ZIMUserInfo();
        zimUserInfo.userID = userInfo.getUserID();
        zimUserInfo.userName = userInfo.getUserName();
        ZegoZIMManager.getInstance().zim.login(zimUserInfo, token, errorInfo -> {
            Log.d(TAG, "onLoggedIn() called with: errorInfo = [" + errorInfo.code + ", "
                    + errorInfo.message + "]");
            if (errorInfo.code == ZIMErrorCode.SUCCESS) {
                localUserInfo = new ZegoUserInfo();
                localUserInfo.setUserID(userInfo.getUserID());
                localUserInfo.setUserName(userInfo.getUserName());
            }
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
            }
        });
    }

    // user logout
    public void logout() {
        Log.d(TAG, "logout() called");
        ZegoZIMManager.getInstance().zim.logout();
        leaveRoom();
    }

    void leaveRoom() {
        userList.clear();
        userMap.clear();
    }

    // get online room users list
    public void getOnlineRoomUsers(int page, ZegoOnlineRoomUserListCallback callback) {
        ZegoRoomInfo roomInfo = ZegoRoomManager.getInstance().roomService.roomInfo;
        ZIMQueryMemberConfig config = new ZIMQueryMemberConfig();
        config.count = 1000;
        ZegoZIMManager.getInstance().zim.queryRoomMember(roomInfo.getRoomID(), config, new ZIMMemberQueriedCallback() {
            @Override
            public void onMemberQueried(ArrayList<ZIMUserInfo> memberList, String nextFlag, ZIMError errorInfo) {
                if (callback != null) {
                    List<ZegoUserInfo> userList = generateRoomUsers(memberList);
                    callback.onUserListCallback(errorInfo.code.value(), userList);
                }
            }
        });
    }

    // get online room users num
    public void getOnlineRoomUsersNum(ZegoOnlineRoomUsersNumCallback callback) {
        ZegoRoomInfo roomInfo = ZegoRoomManager.getInstance().roomService.roomInfo;
        ZegoZIMManager.getInstance().zim.queryRoomOnlineMemberCount(roomInfo.getRoomID(), (count, errorInfo) -> {
            if (callback != null) {
                callback.onUserCountCallback(errorInfo.code.value(), count);
            }
        });
    }

    // send an invitation message to add Co-Host
    public void addCoHost(String userID, ZegoRoomCallback callback) {
        ZegoCustomCommand command = new ZegoCustomCommand();
        command.actionType = ZegoCustomCommand.CustomCommandType.Invitation;
        command.targetUserIDs = Collections.singletonList(userID);
        command.toJson();
        ZegoZIMManager.getInstance().zim.sendPeerMessage(command, userID, (message, errorInfo) -> {
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
            }
        });
    }

    // Respond to the co-host invitation
    public void respondCoHostInvitation(boolean accept, ZegoRoomCallback callback) {
        ZegoRoomInfo roomInfo = ZegoRoomManager.getInstance().roomService.roomInfo;
        String hostID = roomInfo.getHostID();
        ZegoCustomCommand command = new ZegoCustomCommand();
        command.actionType = ZegoCustomCommand.CustomCommandType.RespondInvitation;
        command.targetUserIDs = Collections.singletonList(hostID);
        command.content = new ZegoCustomCommand.CustomCommandContent(accept);
        command.toJson();
        ZegoZIMManager.getInstance().zim.sendPeerMessage(command, hostID, (message, errorInfo) -> {
            if (callback != null) {
                callback.onRoomCallback(errorInfo.code.value());
            }
        });
    }

    // Request to co-host
    public void requestToCoHost(ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getRequestOrCancelToHostParameters(true);
        ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, callback);
    }

    public void cancelRequestToCoHost(ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getRequestOrCancelToHostParameters(false);
        ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, callback);
    }

    // Respond to the co-host request
    public void respondCoHostRequest(boolean agree, String userID, ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getRespondCoHostParameters(agree, userID);
        ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, callback);
    }

    // Prohibit turning on the camera microphone
    public void muteUser(boolean isMuted, String userID, ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getSeatChangeParameters(userID, isMuted, 0);
        if (triple != null) {
            ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, callback);
        }
    }

    // Microphone operate
    public void micOperate(boolean open, ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getSeatChangeParameters(localUserInfo.getUserID(), open, 1);
        if (triple != null) {
            ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, errorCode -> {
                if (errorCode == ZegoRoomErrorCode.SUCCESS) {
                    ZegoExpressEngine.getEngine().muteMicrophone(!open);
                } else {
                    if (callback != null) {
                        callback.onRoomCallback(errorCode);
                    }
                }
            });
        }
    }

    // Camera operate
    public void cameraOperate(boolean open, ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getSeatChangeParameters(localUserInfo.getUserID(), open, 2);
        if (triple != null) {
            ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, errorCode -> {
                if (errorCode == ZegoRoomErrorCode.SUCCESS) {
                    ZegoExpressEngine.getEngine().enableCamera(open);
                } else {
                    if (callback != null) {
                        callback.onRoomCallback(errorCode);
                    }
                }
            });
        }
    }

    // take a co-host seat
    public void takeCoHostSeat(ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getTakeOrLeaveSeatParameters(true);
        ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, errorCode -> {
            if (errorCode == ZegoRoomErrorCode.SUCCESS) {
                ZegoExpressEngine.getEngine().startPublishingStream(getSelfStreamID());
            } else {
                if (callback != null) {
                    callback.onRoomCallback(errorCode);
                }
            }
        });
    }

    // Leave co-host seat
    public void leaveCoHostSeat(ZegoRoomCallback callback) {
        Triple<HashMap<String, String>, String, ZIMRoomAttributesSetConfig> triple
                = ZegoRoomAttributesHelper.getTakeOrLeaveSeatParameters(false);
        ZegoRoomAttributesHelper.setRoomAttributes(triple.first, triple.second, triple.third, errorCode -> {
            if (errorCode == ZegoRoomErrorCode.SUCCESS) {
                ZegoExpressEngine.getEngine().stopPublishingStream();
            } else {
                if (callback != null) {
                    callback.onRoomCallback(errorCode);
                }
            }
        });
    }

    public void setListener(ZegoUserServiceListener listener) {
        this.listener = listener;
    }

    public void onRoomMemberJoined(ZIM zim, ArrayList<ZIMUserInfo> memberList, String roomID) {
        List<ZegoUserInfo> joinUsers = generateRoomUsers(memberList);
        userList.addAll(joinUsers);
        for (ZegoUserInfo joinUser : joinUsers) {
            userMap.put(joinUser.getUserID(), joinUser);
        }
        if (listener != null) {
            listener.onRoomUserJoin(joinUsers);
        }
    }

    public void onRoomMemberLeft(ZIM zim, ArrayList<ZIMUserInfo> memberList, String roomID) {
        List<ZegoUserInfo> leaveUsers = generateRoomUsers(memberList);
        userList.removeAll(leaveUsers);
        for (ZegoUserInfo leaveUser : leaveUsers) {
            userMap.remove(leaveUser.getUserID());
        }
        if (listener != null) {
            listener.onRoomUserLeave(leaveUsers);
        }
    }

    private List<ZegoUserInfo> generateRoomUsers(List<ZIMUserInfo> memberList) {
        ZegoRoomInfo roomInfo = ZegoRoomManager.getInstance().roomService.roomInfo;

        List<ZegoUserInfo> roomUsers = new ArrayList<>();
        for (ZIMUserInfo userInfo : memberList) {
            ZegoUserInfo roomUser = new ZegoUserInfo();
            roomUser.setUserID(userInfo.userID);
            roomUser.setUserName(userInfo.userName);

            if (userInfo.userID.equals(roomInfo.getHostID())) {
                roomUser.setRole(ZegoRoomUserRole.Host);
            } else {
                roomUser.setRole(ZegoRoomUserRole.Participant);
            }
            roomUsers.add(roomUser);
        }
        return roomUsers;
    }

    @NonNull
    private String getSelfStreamID() {
        String selfUserID = ZegoRoomManager.getInstance().userService.localUserInfo.getUserID();
        String roomID = ZegoRoomManager.getInstance().roomService.roomInfo.getRoomID();
        return String.format("%s_%s_%s", roomID, selfUserID, "main");
    }

    public List<ZegoUserInfo> getUserList() {
        return userList;
    }

    public String getUserName(String userID) {
        ZegoUserInfo zegoUserInfo = userMap.get(userID);
        if (zegoUserInfo != null) {
            return zegoUserInfo.getUserName();
        } else {
            return "";
        }
    }

    public void onRoomAttributesUpdated(HashMap<String, String> roomAttributes, OperationCommand command) {
        String myUserID = localUserInfo.getUserID();
        OperationAction action = command.getAction();

        if (!Objects.equals(myUserID, action.getTargetID())) return;

        switch (action.getType()) {
            case RequestToCoHost:
                if (listener != null) {
                    listener.onReceiveToCoHostRequest();
                }
                break;
            case CancelRequestCoHost:
                if (listener != null) {
                    listener.onReceiveCancelToCoHostRequest();
                }
                break;
            case AgreeToCoHost:
                if (listener != null) {
                    listener.onReceiveToCoHostRespond(true);
                }
                break;
            case DeclineToCoHost:
                if (listener != null) {
                    listener.onReceiveToCoHostRespond(false);
                }
                break;
        }

        ZegoCoHostSeatModel seat = null;
        for (ZegoCoHostSeatModel model : command.getSeatList()) {
            if (Objects.equals(model.getUserID(), myUserID)) {
                seat = model;
            }
        }

        if (seat == null) return;

        if (action.getType() == OperationActionType.Mic) {
            ZegoExpressEngine.getEngine().muteMicrophone(!seat.isMicEnable());
        }
        if (action.getType() == OperationActionType.Camera) {
            ZegoExpressEngine.getEngine().enableCamera(!seat.isCameraEnable());
        }

        if (action.getType() == OperationActionType.Mute && seat.isMuted()) {
            ZegoExpressEngine.getEngine().muteMicrophone(true);
            micOperate(false, null);
        }
    }

    public void onReceivePeerMessage(ZIM zim, ArrayList<ZIMMessage> messageList, String fromUserID) {
        for (ZIMMessage zimMessage : messageList) {
            if (zimMessage.type == ZIMMessageType.CUSTOM) {
                ZIMCustomMessage zimCustomMessage = (ZIMCustomMessage) zimMessage;
                ZegoCustomCommand command = new ZegoCustomCommand();
                command.type = zimCustomMessage.type;
                command.userID = zimCustomMessage.userID;
                command.fromJson(zimCustomMessage.message);
                if (command.actionType == ZegoCustomCommand.CustomCommandType.Invitation) {
                    if (listener != null) {
                        listener.onReceiveAddCoHostInvitation();
                    }
                } else {
                    ZegoCustomCommand.CustomCommandContent content = command.content;
                    if (content == null) continue;
                    if (listener != null) {
                        listener.onReceiveAddCoHostRespond(content.accept);
                    }
                }
            }
        }
    }
}