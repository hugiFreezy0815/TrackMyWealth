package archfixture.controller;

import archfixture.model.StrayEntity;

/** Fixture: a controller returning an entity that lives outside the entity package. */
public class StrayEntityController {

  public StrayEntity leak() {
    return new StrayEntity();
  }
}
