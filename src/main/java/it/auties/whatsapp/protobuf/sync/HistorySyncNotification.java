package it.auties.whatsapp.protobuf.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.Arrays;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class HistorySyncNotification {
  @JsonProperty("8")
  @JsonPropertyDescription("string")
  private String originalMessageId;

  @JsonProperty("7")
  @JsonPropertyDescription("uint32")
  private int chunkOrder;

  @JsonProperty("6")
  @JsonPropertyDescription("HistorySyncNotificationHistorySyncType")
  private HistorySyncNotificationHistorySyncType syncType;

  @JsonProperty("5")
  @JsonPropertyDescription("string")
  private String directPath;

  @JsonProperty("4")
  @JsonPropertyDescription("bytes")
  private byte[] fileEncSha256;

  @JsonProperty("3")
  @JsonPropertyDescription("bytes")
  private byte[] mediaKey;

  @JsonProperty("2")
  @JsonPropertyDescription("uint64")
  private long fileLength;

  @JsonProperty("1")
  @JsonPropertyDescription("bytes")
  private byte[] fileSha256;

  @Accessors(fluent = true)
  public enum HistorySyncNotificationHistorySyncType {
    INITIAL_BOOTSTRAP(0),
    INITIAL_STATUS_V3(1),
    FULL(2),
    RECENT(3),
    PUSH_NAME(4);

    private final @Getter int index;

    HistorySyncNotificationHistorySyncType(int index) {
      this.index = index;
    }

    @JsonCreator
    public static HistorySyncNotificationHistorySyncType forIndex(int index) {
      return Arrays.stream(values())
          .filter(entry -> entry.index() == index)
          .findFirst()
          .orElse(null);
    }
  }
}