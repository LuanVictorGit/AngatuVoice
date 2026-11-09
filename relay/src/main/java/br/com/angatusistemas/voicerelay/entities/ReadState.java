package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * How far one person has read one chat, shared by all of their devices. The id is the person and the chat joined by a bar.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class ReadState extends Saveable {

    private String id;
    private long lastSeq;
}
