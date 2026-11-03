package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A chat message in a group or a direct conversation, with the delivery and read state kept locally.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Message extends Saveable {

    private String id;
    private String chatId;
    private String senderId;
    private String senderName;
    private String text;
    private long sentAt;
    private boolean pending;
    private boolean read;
    private String authorKey;
    private String signature;
}