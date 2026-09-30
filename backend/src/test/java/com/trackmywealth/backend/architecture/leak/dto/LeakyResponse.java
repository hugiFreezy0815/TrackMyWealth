package com.trackmywealth.backend.architecture.leak.dto;

import com.trackmywealth.backend.architecture.leak.entity.LeakedEntity;

/** Test fixture only: a DTO carrying an entity, which the DTO rule must reject. */
public record LeakyResponse(LeakedEntity entity) {}
