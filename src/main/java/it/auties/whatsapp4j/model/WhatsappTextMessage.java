package it.auties.whatsapp4j.model;

import it.auties.whatsapp4j.api.WhatsappAPI;
import it.auties.whatsapp4j.builder.WhatsappTextMessageBuilder;
import it.auties.whatsapp4j.utils.ProtobufUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A model class that represents a WhatsappMessage sent by a contact and that holds text inside.
 * This class is only a model, this means that changing its values will have no real effect on WhatsappWeb's servers.
 * Instead, methods inside {@link WhatsappAPI} should be used.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(fluent = true)
@ToString
public final class WhatsappTextMessage extends WhatsappUserMessage {
    /**
     * The text wrapped by this object
     */
    protected final @NotNull String text;

    /**
     * Constructs a WhatsappTextMessage from a raw protobuf object
     *
     * @param info the raw protobuf to wrap
     */
    public WhatsappTextMessage(@NotNull WhatsappProtobuf.WebMessageInfo info) {
        super(info, info.getMessage().hasExtendedTextMessage() || info.getMessage().hasConversation());
        this.text = info.getMessage().hasConversation() ? info.getMessage().getConversation() : info.getMessage().getExtendedTextMessage().getText();
    }

    /**
     * Constructs a new {@link WhatsappTextMessageBuilder} to build a new message that can be later sent using {@link WhatsappAPI#sendMessage(WhatsappUserMessage)}
     *
     * @return a non null WhatsappTextMessageBuilder
     */
    public static @NotNull WhatsappTextMessageBuilder newTextMessage(){
        return new WhatsappTextMessageBuilder();
    }

    /**
     * Constructs a WhatsappTextMessage from the metadata provided.
     * This message can be sent using {@link WhatsappAPI#sendMessage(WhatsappUserMessage)}.
     *
     * @param chat the chat where for this message
     * @param text the text of this message
     * @return a non null WhatsappTextMessage
     */
    public static @NotNull WhatsappTextMessage newTextMessage(@NotNull WhatsappChat chat, @NotNull String text){
        return new WhatsappTextMessage(ProtobufUtils.createMessageInfo(ProtobufUtils.createTextMessage(text, null, false), chat.jid()));
    }

    /**
     * Constructs a WhatsappTextMessage from the metadata provided.
     * This message can be sent using {@link WhatsappAPI#sendMessage(WhatsappUserMessage)}.
     *
     * @param chat the chat where for this message
     * @param text the text of this message
     * @param quotedMessage the message that this message quotes
     * @return a non null WhatsappTextMessage
     */
    public static @NotNull WhatsappTextMessage newTextMessage(@NotNull WhatsappChat chat, @NotNull String text, @NotNull WhatsappUserMessage quotedMessage){
        return new WhatsappTextMessage(ProtobufUtils.createMessageInfo(ProtobufUtils.createTextMessage(text, quotedMessage, false), chat.jid()));
    }

    /**
     * Constructs a WhatsappTextMessage from the metadata provided.
     * This message can be sent using {@link WhatsappAPI#sendMessage(WhatsappUserMessage)}.
     *
     * @param chat the chat where for this message
     * @param text the text of this message
     * @param forwarded whether this message should be marked as forwarded
     * @return a non null WhatsappTextMessage
     */
    public static @NotNull WhatsappTextMessage newTextMessage(@NotNull WhatsappChat chat, @NotNull String text, boolean forwarded){
        return new WhatsappTextMessage(ProtobufUtils.createMessageInfo(ProtobufUtils.createTextMessage(text, null, forwarded), chat.jid()));
    }

    /**
     * Constructs a WhatsappTextMessage from the metadata provided.
     * This message can be sent using {@link WhatsappAPI#sendMessage(WhatsappUserMessage)}.
     *
     * @param chat the chat where for this message
     * @param text the text of this message
     * @param quotedMessage the message that this message quotes, can be null
     * @param forwarded whether this message should be marked as forwarded
     * @return a non null WhatsappTextMessage
     */
    public static @NotNull WhatsappTextMessage newTextMessage(@NotNull WhatsappChat chat, @NotNull String text, @Nullable WhatsappUserMessage quotedMessage, boolean forwarded){
        return new WhatsappTextMessage(ProtobufUtils.createMessageInfo(ProtobufUtils.createTextMessage(text, quotedMessage, forwarded), chat.jid()));
    }


    /**
     * Returns the ContextInfo of this message if available
     *
     * @return a non empty optional if this message has a context
     */
    @Override
    public @NotNull Optional<WhatsappProtobuf.ContextInfo> contextInfo() {
        if(!info.getMessage().hasExtendedTextMessage()){
            return Optional.empty();
        }

        return info.getMessage().getExtendedTextMessage().hasContextInfo() ? Optional.of(info.getMessage().getExtendedTextMessage().getContextInfo()) : Optional.empty();
    }
}
