package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

/**
 * A known Radmin VPN network managed or monitored by AngatuVoice.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class RadminNetwork extends Saveable {

    private String id;
    private String name;
    private String kind;
    private boolean protectedNetwork;
    private String status;
    private long lastSeenAt;
    private long createdAt;
}
