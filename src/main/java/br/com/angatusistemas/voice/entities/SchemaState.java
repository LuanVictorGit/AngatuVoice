package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * Holds the schema version of the local database so migrations run once and in order.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class SchemaState extends Saveable {

    private String id;
    private int version;
}