package br.com.angatusistemas.voicerelay.entities;

import br.com.angatusistemas.lib.database.Saveable;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * A chat message as the server keeps it: the only copy there is, read in pages by every app and removed only when the
 * message storage reaches its limit. Legacy rows also keep the signed frame old apps redeliver.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class StoredMessage extends Saveable {

    private String id;
    private long seq;
    private String chat;
    private String recipient;
    private String sender;
    private String name;
    private long ts;
    private long edited;
    private String text;
    private String replyTo;
    private List<String> mentions;
    private Map<String, List<String>> reactions;
    private List<AttachmentRef> attachments;
    private List<Embed> embeds;
    private String nonce;
    private String frame;
    private long pinned;
    private String pinnedBy;

    public List<String> getMentions() {
        return mentions == null ? List.of() : mentions;
    }

    public Map<String, List<String>> getReactions() {
        return reactions == null ? Map.of() : reactions;
    }

    public List<AttachmentRef> getAttachments() {
        return attachments == null ? List.of() : attachments;
    }

    public List<Embed> getEmbeds() {
        return embeds == null ? List.of() : embeds;
    }

    public record AttachmentRef(String id, String name, String type, long size, int width, int height, boolean expired) {

        public AttachmentRef asExpired() {
            return new AttachmentRef(id, name, type, size, width, height, true);
        }
    }

    public record Embed(String url, String title, String description, String site, String color, String imageUrl,
            int imageWidth, int imageHeight) {
    }
}
