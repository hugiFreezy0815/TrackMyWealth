package com.trackmywealth.backend.architecture.leak.controller;

import com.trackmywealth.backend.architecture.leak.entity.LeakedEntity;

/** Test fixture only: a controller returning an entity, which the controller rule must reject. */
public class LeakyController {

  public LeakedEntity leak() {
    return new LeakedEntity();
  }
}
