package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A private group as the server sees it: only the token that proves membership, never its name or secret.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class GroupRecord extends Saveable {

    private String id;
    private String token;
}
