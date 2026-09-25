package io.github.markpollack.experiment.recording;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Immutable producer text with an identity independent of any compatibility projection.
 *
 * @param sourceKind JUDGMENT or VERDICT
 * @param producerJson complete original JSON object, frozen once
 * @param sha256 SHA-256 of the UTF-8 source text
 * @param associations consumer identities outside the producer document
 */
public record ProducerDocument(String sourceKind, String producerJson, String sha256,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<Association> associations) {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	/** A consumer association at an existing verdict node. */
	public record Association(String verdictPointer, String instrumentHash) {
		public Association {
			Objects.requireNonNull(verdictPointer, "verdictPointer");
			if (instrumentHash == null || instrumentHash.isBlank())
				throw new IllegalArgumentException("instrumentHash is required");
		}
	}

	public ProducerDocument {
		if (!List.of("JUDGMENT", "VERDICT").contains(sourceKind))
			throw new IllegalArgumentException("Unsupported producer source kind");
		Objects.requireNonNull(producerJson, "producerJson");
		if (!hash(producerJson.getBytes(StandardCharsets.UTF_8)).equals(sha256))
			throw new IllegalArgumentException("Producer source hash mismatch");
		JsonNode source = parse(producerJson);
		if (!source.isObject())
			throw new IllegalArgumentException("Producer source must be a JSON object");
		associations = associations == null ? List.of() : List.copyOf(associations);
		if (!associations.isEmpty() && !sourceKind.equals("VERDICT"))
			throw new IllegalArgumentException("Associations require a verdict source");
		for (Association association : associations) {
			JsonNode target = source.at(association.verdictPointer());
			if (!target.isObject() || !target.has("aggregated"))
				throw new IllegalArgumentException("Association does not identify a verdict node");
		}
		if (associations.stream().map(Association::verdictPointer).distinct().count() != associations.size())
			throw new IllegalArgumentException("Duplicate consumer association pointer");
	}

	/** Freeze the producer's ordinary Jackson projection exactly once. */
	public static ProducerDocument from(String kind, Object value) {
		return text(kind, json(value));
	}

	/** Retain supplied text without normalizing it. */
	public static ProducerDocument text(String kind, String text) {
		return new ProducerDocument(kind, text, hash(text.getBytes(StandardCharsets.UTF_8)), List.of());
	}

	/** Add associations without touching producer text. */
	public ProducerDocument withAssociations(List<Association> values) {
		return new ProducerDocument(sourceKind, producerJson, sha256, values);
	}

	/** A disposable view; mutations cannot alter the source. */
	public JsonNode node() {
		return parse(producerJson);
	}

	/** Scalar mapping matches the producer's ordinary ObjectMapper. */
	public Map<String, Object> producerMap() {
		try {
			return JSON.readValue(producerJson, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Malformed producer source", e);
		}
	}

	/** Read a supported live value. Unsupported inputs remain accessible as source. */
	public <T> T value(Class<T> type) {
		try {
			return JSON.readValue(producerJson, type);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Source has no supported typed view", e);
		}
	}

	/** Serialize with the ordinary producer Jackson configuration. */
	public static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Cannot capture JSON source", e);
		}
	}

	/** Strict syntax parsing, including duplicate members and trailing content. */
	public static JsonNode parse(String text) {
		try {
			return JSON.readTree(text);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Malformed JSON source", e);
		}
	}

	/** Hash arbitrary recorded bytes. */
	public static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
