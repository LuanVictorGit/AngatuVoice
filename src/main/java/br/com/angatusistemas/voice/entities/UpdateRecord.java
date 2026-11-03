package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * One entry of the update history: what was attempted, when, and how it ended.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class UpdateRecord extends Saveable {

    private String id;
    private String version;
    private String outcome;
    private String detail;
    private long at;
}