package io.beacon.query.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

public final class CursorCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CursorCodec() {}

  public static String encode(List<String> sortValues) {
    try {
      byte[] json = MAPPER.writeValueAsBytes(sortValues);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to encode cursor", e);
    }
  }

  public static List<String> decode(String cursor) {
    try {
      byte[] json = Base64.getUrlDecoder().decode(cursor);
      return MAPPER.readValue(json, new TypeReference<>() {});
    } catch (IllegalArgumentException | IOException e) {
      throw new IllegalArgumentException("Invalid cursor", e);
    }
  }
}
