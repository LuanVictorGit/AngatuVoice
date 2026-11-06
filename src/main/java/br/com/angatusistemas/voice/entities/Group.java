package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A conversation space: the chat everyone on the server shares, or a private group protected by an invite secret.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Group extends Saveable {

    private String id;
    private String name;
    private String kind;
    private String secret;
    private long createdAt;
}