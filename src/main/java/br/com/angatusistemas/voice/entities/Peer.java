package br.com.angatusistemas.voice.entities;

import br.com.angatusistemas.lib.database.Saveable;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * A remote participant, pinned by the fingerprint of the Ed25519 key it proved at the handshake.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class Peer extends Saveable {

    private String id;
    private String name;
    private String radminAlias;
    private String publicKey;
    private String lastIp;
    private int lastPort;
    private long firstSeen;
    private long lastSeen;
    private String avatar;
    private List<String> groups = new ArrayList<>();
}