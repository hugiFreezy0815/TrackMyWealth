package archfixture.dto;

import archfixture.model.StrayEntity;

/** Fixture: a DTO carrying an entity that lives outside the entity package. */
public record StrayEntityResponse(StrayEntity entity) {}
