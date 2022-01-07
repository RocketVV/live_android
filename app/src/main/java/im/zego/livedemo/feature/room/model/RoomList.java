package im.zego.livedemo.feature.room.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RoomList {

    @SerializedName("room_list")
    public List<RoomBean> roomList;

    @Override
    public String toString() {
        return "RoomList{" +
            "roomList=" + roomList +
            '}';
    }
}
