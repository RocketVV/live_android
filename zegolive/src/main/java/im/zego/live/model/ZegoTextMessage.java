package im.zego.live.model;

import im.zego.zim.entity.ZIMTextMessage;

public class ZegoTextMessage extends ZIMTextMessage implements Comparable<ZegoTextMessage> {
    @Override
    public int compareTo(ZegoTextMessage o) {
        return (int) (timestamp - o.timestamp);
    }
}
