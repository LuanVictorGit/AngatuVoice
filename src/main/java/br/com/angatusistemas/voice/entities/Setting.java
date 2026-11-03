package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A single persisted preference, keyed by its setting name and stored as text.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Setting extends Saveable {

    private String id;
    private String value;
}