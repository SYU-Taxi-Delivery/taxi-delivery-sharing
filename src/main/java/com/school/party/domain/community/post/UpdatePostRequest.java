package com.school.party.domain.community.post;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Retains field presence, so omitted fields and explicit JSON null are different. */
public record UpdatePostRequest(JsonNode body) {
    public UpdatePostRequest { body = body == null ? null : body.deepCopy(); }
    public boolean has(String field) { return body != null && body.has(field); }
}
