package it.auties.whatsapp.model.button;

import com.fasterxml.jackson.annotation.JsonCreator;
import it.auties.bytes.Bytes;
import it.auties.protobuf.api.model.ProtobufMessage;
import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.info.NativeFlowInfo;
import it.auties.whatsapp.model.message.button.ButtonsMessage;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.MESSAGE;
import static it.auties.protobuf.api.model.ProtobufProperty.Type.STRING;

@AllArgsConstructor
@Data
@Builder(builderMethodName = "newRawButtonBuilder")
@Jacksonized
@Accessors(fluent = true)
public class Button implements ProtobufMessage {
    @ProtobufProperty(index = 1, type = STRING)
    private String buttonId;

    @ProtobufProperty(index = 2, type = MESSAGE, concreteType = ButtonText.class)
    private ButtonText buttonText;

    @ProtobufProperty(index = 3, type = MESSAGE, concreteType = ButtonType.class)
    private ButtonType type;

    @ProtobufProperty(index = 4, type = MESSAGE, concreteType = NativeFlowInfo.class)
    private NativeFlowInfo nativeFlowInfo;

    /**
     * Constructs a new builder to create a response text button.
     * The result can be later sent using {@link Whatsapp#sendMessage(MessageInfo)}.
     *
     * @param text the non-null text of this button
     * @return a non-null button
     */
    public static Button newTextResponseButton(@NonNull String text) {
        return newTextResponseButton(Bytes.ofRandom(6)
                .toHex(), text);
    }

    /**
     * Constructs a new builder to create a response text button.
     * The result can be later sent using {@link Whatsapp#sendMessage(MessageInfo)}.
     *
     * @param id   the non-null id of the button
     * @param text the non-null text of this button
     * @return a non-null button
     */
    public static Button newTextResponseButton(@NonNull String id, @NonNull String text) {
        return Button.newRawButtonBuilder()
                .buttonId(id)
                .buttonText(new ButtonText(text))
                .type(ButtonType.RESPONSE)
                .build();
    }

    @AllArgsConstructor
    @Accessors(fluent = true)
    public enum ButtonType implements ProtobufMessage {
        UNKNOWN(0),
        RESPONSE(1),
        NATIVE_FLOW(2);

        @Getter
        private final int index;

        @JsonCreator
        public static ButtonType forIndex(int index) {
            return Arrays.stream(values())
                    .filter(entry -> entry.index() == index)
                    .findFirst()
                    .orElse(null);
        }
    }
}
