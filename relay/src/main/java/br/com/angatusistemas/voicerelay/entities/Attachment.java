package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A file uploaded to the server. It belongs to its uploader until a message takes it, and from then on to that message's chat.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Attachment extends Saveable {

    private String id;
    private String owner;
    private String name;
    private String type;
    private long size;
    private int width;
    private int height;
    private long createdAt;
    private String message;
    private String chat;
}
